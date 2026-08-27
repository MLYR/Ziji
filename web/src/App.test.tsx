import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { clearWebSession } from '@/auth/auth-session'
import App from './App'

const session = {
  id: 'session-1',
  deviceName: 'Web 浏览器',
  deviceId: null,
  createdAt: '2026-08-26T00:00:00Z',
  lastSeenAt: '2026-08-26T00:00:00Z',
  status: 'ACTIVE',
}

const user = {
  id: 'user-1',
  email: 'demo@example.com',
  nickname: '演示用户',
  timezone: 'Asia/Shanghai',
  baseCurrency: 'CNY',
  locale: 'zh-CN',
  amountFormat: 'STANDARD',
  status: 'ACTIVE',
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
  return render(<MemoryRouter initialEntries={initialEntries}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>)
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
    renderApp(['/dashboard'])
    expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument()
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

    return waitFor(() => expect(screen.getByRole('heading', { name: '总览基础设施已就绪' })).toBeInTheDocument()).then(() => {
      expect(screen.getByText('这不是零余额；在账户和账务模块实现前，页面保持明确的未加载状态。')).toBeInTheDocument()
      expect(fetchMock).toHaveBeenCalledTimes(2)
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
    await waitFor(() => expect(screen.getByRole('heading', { name: '总览基础设施已就绪' })).toBeInTheDocument())
  })
})
