import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { clearWebSession, getWebAuthSnapshot, resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import App from './App'


const dashboardData = {
  data: {
    baseCurrency: 'CNY',
    asOf: '2026-08-28T00:00:00Z',
    asOfSequence: 0,
    valuationRevision: 1,
    recalculatedAt: '2026-08-28T00:00:00Z',
    projectionStatus: 'CURRENT',
    summary: { totalAssets: '70000.00', availableFunds: '20000.00', investmentAssets: '50000.00', totalLiabilities: '2300.00', netAssets: '67700.00' },
    changeAttribution: { income: '0.00', expense: '0.00', market: '0.00', fx: '0.00', adjustment: '0.00', inclusion: '0.00' },
    distribution: [],
    investmentOverview: { baseCurrency: 'CNY', brokerCash: '50000.00', positionMarketValue: '0.00', totalInvestmentAssets: '50000.00', unpricedInstrumentCount: 0 },
    dataQualityWarnings: [],
  },
  meta: { requestId: 'req-dash' },
}

const emptySeries = (baseCurrency: string) => ({
  data: { baseCurrency, valuationRevision: 1, points: [] },
  meta: { requestId: 'req-series' },
})

// 总览页登录后拉取的核心指标、趋势、结构与近期流水契约。
function mockBusinessEndpoints(fetchMock: ReturnType<typeof vi.spyOn>) {
  fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.includes('/api/v1/dashboard')) return jsonResponse(dashboardData)
    if (path.includes('/api/v1/statistics/assets')) return jsonResponse(emptySeries('CNY'))
    if (path.includes('/api/v1/statistics/accounts')) return jsonResponse(emptySeries('CNY'))
    if (path.includes('/api/v1/transactions')) return jsonResponse({ data: [], meta: { requestId: 'req-tx', nextCursor: null, hasMore: false } })
    throw new Error(`unexpected fetch ${path}`)
  })
}

const session = {
  id: 'session-1',
  deviceName: 'Web 浏览器',
  deviceId: null,
  createdAt: '2026-08-26T00:00:00Z',
  lastSeenAt: '2026-08-26T00:00:00Z',
  status: 'ACTIVE' as const,
}

const user = {
  id: 'user-1',
  email: 'demo@example.com',
  nickname: '演示用户',
  timezone: 'Asia/Shanghai',
  baseCurrency: 'CNY',
  locale: 'zh-CN',
  amountFormat: 'STANDARD' as const,
  status: 'ACTIVE' as const,
  version: 1,
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function renderApp(initialEntries: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(<StrictMode><MemoryRouter initialEntries={initialEntries}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter></StrictMode>)
  return { ...view, client }
}

function fillLoginForm() {
  fireEvent.change(screen.getByLabelText(/邮箱地址/), { target: { value: user.email } })
  fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'long-enough-password' } })
}

describe('应用壳', () => {
  afterEach(() => {
    // 认证态是内存单例；测试结束必须清理，避免下一个路由用例继承登录状态。
    cleanup()
    clearWebSession()
    vi.restoreAllMocks()
  })

  it('未认证访问业务路径时进入登录页', () => {
    resetWebSessionForTests()
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(jsonResponse({ title: 'Unauthorized', status: 401, code: 'AUTHENTICATION_REQUIRED', requestId: 'request-1' }, 401))
    renderApp(['/dashboard'])
    return waitFor(() => expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument())
  })

  it('受保护路由恢复会话后加载用户资料，StrictMode 重复渲染不重复刷新', async () => {
    resetWebSessionForTests()
    document.cookie = 'ziji_csrf=csrf-test; path=/'
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ data: { session, accessToken: 'access-test', expiresIn: 1800 }, meta: {} }))
      .mockResolvedValueOnce(jsonResponse({ data: user, meta: {} }))
    mockBusinessEndpoints(fetchMock)
    renderApp(['/dashboard'])

    await waitFor(() => expect(screen.getByRole('heading', { name: '总览' })).toBeInTheDocument())
    expect(fetchMock.mock.calls.slice(0, 2).map(([path]) => path)).toEqual(['/api/v1/auth/web/sessions/refresh', '/api/v1/users/me'])
  })

  it('展示明确的未加载状态而不是伪造财务数据', () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
    // 通过真实登录编排建立受保护壳需要的短期 token 和服务端用户资料。
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ data: { session, accessToken: 'access-test', expiresIn: 1800 }, meta: {} }, 201))
      .mockResolvedValueOnce(jsonResponse({ data: user, meta: {} }))
    renderApp(['/login'])
    fillLoginForm()
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    mockBusinessEndpoints(fetchMock)
    return waitFor(() => expect(screen.getByRole('heading', { name: '总览' })).toBeInTheDocument()).then(() => {
      // 登录后总览展示服务端核心指标，而非占位或伪造数据。
      expect(screen.getByTestId('metric-净资产').textContent).toContain('67700.00')
      expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/web/sessions')
      expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/users/me')
      expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Authorization')).toBe('Bearer access-test')
    })
  })

  it('登录失败展示不泄露账号存在性的统一文案', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(jsonResponse({
      type: 'about:blank',
      title: 'Unauthorized',
      status: 401,
      code: 'INVALID_CREDENTIALS',
      requestId: 'request-1',
    }, 401, { 'Content-Type': 'application/problem+json' }))
    renderApp(['/login'])
    fillLoginForm()
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(screen.getByText('邮箱或密码不正确，请检查后重试。')).toBeInTheDocument())
    expect(screen.queryByText(/邮箱不存在|账号不存在/)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('空值提交只展示字段错误且不发起请求', () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
    renderApp(['/login'])
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(screen.getByText('请输入有效的邮箱地址。')).toBeInTheDocument()
    expect(screen.getByText('请输入密码。')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('登录请求处理中禁用提交按钮并显示加载文案', async () => {
    let resolveSession!: (response: Response) => void
    const pendingSession = new Promise<Response>((resolve) => { resolveSession = resolve })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockReturnValueOnce(pendingSession)
    fetchMock.mockResolvedValueOnce(jsonResponse({ data: user, meta: {} }))
    renderApp(['/login'])
    fillLoginForm()
    const submitButton = screen.getByRole('button', { name: '登录' })
    fireEvent.click(submitButton)

    await waitFor(() => expect(submitButton).toBeDisabled())
    expect(submitButton).toHaveTextContent('登录中…')
    resolveSession(jsonResponse({ data: { session, accessToken: 'access-test', expiresIn: 1800 }, meta: {} }, 201))
    mockBusinessEndpoints(fetchMock)
    await waitFor(() => expect(screen.getByRole('heading', { name: '总览' })).toBeInTheDocument())
  })

  it('设备会话支持加载更多，并明确标记当前设备', async () => {
    setWebSession({ session, accessToken: 'access-test', expiresIn: 1800 })
    setWebUser(user)
    const nextSession = { ...session, id: 'session-2', deviceName: '另一台浏览器' }
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ data: [session], meta: { requestId: 'request-1', nextCursor: 'next-page', hasMore: true } }))
      .mockResolvedValueOnce(jsonResponse({ data: [nextSession], meta: { requestId: 'request-2', nextCursor: null, hasMore: false } }))
    // 新流水页会读取业务数据；这些壳层用例只验证应用外壳和会话，改用无请求占位路由。
    renderApp(['/investments'])

    fireEvent.click(screen.getByRole('button', { name: '设备与会话' }))
    await waitFor(() => expect(screen.getByText('这是当前设备')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '加载更多设备' }))
    await waitFor(() => expect(screen.getByText('另一台浏览器')).toBeInTheDocument())
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/users/me/sessions?limit=20',
      '/api/v1/users/me/sessions?limit=20&cursor=next-page',
    ])
  })

  it('退出当前设备后清理受保护查询并返回登录页', async () => {
    setWebSession({ session, accessToken: 'access-test', expiresIn: 1800 })
    setWebUser(user)
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(null, { status: 204 }))
    const { client } = renderApp(['/investments'])
    client.setQueryData(['private-data', user.id], { userId: user.id })

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认退出' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument())
    expect(client.getQueryData(['private-data', user.id])).toBeUndefined()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/sessions/current')
  })

  it('撤销其他设备后保留当前登录并刷新设备列表', async () => {
    setWebSession({ session, accessToken: 'access-test', expiresIn: 1800 })
    setWebUser(user)
    const other = { ...session, id: 'session-2', deviceName: '其他设备' }
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ data: [session, other], meta: { requestId: 'request-1', nextCursor: null, hasMore: false } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(jsonResponse({ data: [session], meta: { requestId: 'request-2', nextCursor: null, hasMore: false } }))
    renderApp(['/investments'])

    fireEvent.click(screen.getByRole('button', { name: '设备与会话' }))
    await waitFor(() => expect(screen.getByText('其他设备')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '撤销此设备' }))
    fireEvent.click(screen.getByRole('button', { name: '确认撤销' }))
    await waitFor(() => expect(screen.queryByText('其他设备')).not.toBeInTheDocument())
    expect(getWebAuthSnapshot().user?.id).toBe(user.id)
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/users/me/sessions?limit=20',
      '/api/v1/users/me/sessions/session-2',
      '/api/v1/users/me/sessions?limit=20',
    ])
  })

  it('退出全部设备后清理认证态并返回登录页', async () => {
    setWebSession({ session, accessToken: 'access-test', expiresIn: 1800 })
    setWebUser(user)
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ data: [session], meta: { requestId: 'request-1', nextCursor: null, hasMore: false } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    renderApp(['/investments'])

    fireEvent.click(screen.getByRole('button', { name: '设备与会话' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '退出全部设备' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '退出全部设备' }))
    fireEvent.click(screen.getByRole('button', { name: '确认撤销' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument())
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/users/me/sessions?limit=20',
      '/api/v1/users/me/sessions',
    ])
  })
})
