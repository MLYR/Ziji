import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { RecordTransactionPage } from '@/ledger/RecordTransactionPage'

vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: vi.fn(),
  }
})

import { ApiClientError, apiRequest } from '@/lib/api-client'

const apiRequestMock = vi.mocked(apiRequest)

const user = {
  id: 'user-1',
  email: 'demo@example.com',
  nickname: '演示用户',
  timezone: 'Asia/Shanghai',
  baseCurrency: 'CNY' as const,
  locale: 'zh-CN',
  amountFormat: 'STANDARD' as const,
  status: 'ACTIVE' as const,
  version: 1,
}

const categories = [
  { id: 'cat-food', categoryType: 'EXPENSE', name: '餐饮', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
  { id: 'cat-transport', categoryType: 'EXPENSE', name: '交通', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
  { id: 'cat-salary', categoryType: 'INCOME', name: '工资', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
]

const tags = [
  { id: 'tag-work', name: '工作', status: 'ACTIVE', version: 1 },
]

const accounts = [
  { id: 'account-bank', accountClass: 'ASSET', accountType: 'BANK', name: '工资卡', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 },
  { id: 'card', accountClass: 'LIABILITY', accountType: 'CREDIT_CARD', name: '信用卡', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 },
]

function problem(code: string, status: number, detail: string) {
  return {
    type: `https://ziji.app/problems/${code.toLowerCase().replaceAll('_', '-')}`,
    title: status === 500 ? 'Internal Server Error' : 'Rejected',
    status,
    code,
    requestId: 'req-1',
    detail,
  }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/transactions/new']}>
        <QueryClientProvider client={client}>
          <RecordTransactionPage />
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

function mockAccounts() {
  apiRequestMock.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/accounts')) {
      return { data: accounts, meta: { requestId: 'req-accounts', nextCursor: null, hasMore: false } }
    }
    if (path.startsWith('/api/v1/categories')) {
      return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
    }
    if (path.startsWith('/api/v1/tags')) {
      return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
    }
    throw new Error(`unexpected path ${path}`)
  })
}

async function fillExpenseForm() {
  fireEvent.change(screen.getByLabelText('账户（资产或信用卡）'), { target: { value: 'account-bank' } })
  fireEvent.change(screen.getByLabelText('支出金额'), { target: { value: '12.50' } })
  // 分类选项来自异步查询，先等选项渲染再选择。
  await screen.findByText('餐饮')
  fireEvent.change(screen.getByLabelText('分类'), { target: { value: 'cat-food' } })
}

describe('记一笔表单', () => {
  beforeEach(() => {
    resetWebSessionForTests()
    setWebSession({
      expiresIn: 600,
      accessToken: 'token-1',
      session: {
        id: 'session-1',
        deviceName: 'Web 浏览器',
        deviceId: null,
        createdAt: '2026-08-26T00:00:00Z',
        lastSeenAt: '2026-08-26T00:00:00Z',
        status: 'ACTIVE',
      },
    })
    setWebUser(user)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    resetWebSessionForTests()
  })

  it('提交支出交易时携带幂等键并展示成功状态', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/accounts')) {
        return { data: accounts, meta: { requestId: 'req-accounts', nextCursor: null, hasMore: false } }
      }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      if (path === '/api/v1/transactions') return { data: { id: 'tx-1', type: 'EXPENSE' } }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByText(/工资卡/)
    await fillExpenseForm()
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))

    await screen.findByText('交易已保存')
    expect(screen.getByText(/tx-1/)).toBeTruthy()
    const postCall = apiRequestMock.mock.calls.find(([path]) => path === '/api/v1/transactions')
    expect(postCall).toBeTruthy()
    const options = postCall![1] as { method?: string; headers?: Record<string, string>; body?: Record<string, unknown> }
    expect(options.method).toBe('POST')
    expect(options.headers?.['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/i)
    expect(options.body?.type).toBe('EXPENSE')
    expect(options.body?.amount).toBe('12.50')
    expect(options.body?.currency).toBe('CNY')
  })

  it('重复提交复用同一幂等键，修改金额后换新键', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/accounts')) {
        return { data: accounts, meta: { requestId: 'req-accounts', nextCursor: null, hasMore: false } }
      }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      throw new ApiClientError(problem('INTERNAL_ERROR', 500, '服务器处理请求失败'))
    })

    renderPage()
    await screen.findByText(/工资卡/)
    await fillExpenseForm()

    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))
    await screen.findByText(/服务器处理请求失败/)
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([path]) => path === '/api/v1/transactions')).toHaveLength(2))

    const keys = apiRequestMock.mock.calls
      .filter(([path]) => path === '/api/v1/transactions')
      .map(([, options]) => (options as { headers: Record<string, string> }).headers['Idempotency-Key'])
    expect(keys[0]).toBe(keys[1])

    fireEvent.change(screen.getByLabelText('支出金额'), { target: { value: '20.00' } })
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([path]) => path === '/api/v1/transactions')).toHaveLength(3))
    const keysAfterEdit = apiRequestMock.mock.calls
      .filter(([path]) => path === '/api/v1/transactions')
      .map(([, options]) => (options as { headers: Record<string, string> }).headers['Idempotency-Key'])
    expect(keysAfterEdit[2]).not.toBe(keysAfterEdit[1])
  })

  it('客户端校验金额精度与必填项并阻断提交', async () => {
    mockAccounts()
    renderPage()
    await screen.findByText(/工资卡/)
    fireEvent.change(screen.getByLabelText('账户（资产或信用卡）'), { target: { value: 'account-bank' } })
    fireEvent.change(screen.getByLabelText('支出金额'), { target: { value: '12.345' } })
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))

    await screen.findByText('金额格式需符合 CNY 记账精度')
    expect(screen.getByText('请选择分类')).toBeTruthy()
    expect(apiRequestMock.mock.calls.some(([path]) => path.startsWith('/api/v1/transactions'))).toBe(false)
  })

  it('信用卡消费仍提交公共 EXPENSE 语义命令，不提交分录', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/accounts')) return { data: accounts, meta: { requestId: 'req-accounts', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      if (path === '/api/v1/transactions') return { data: { id: 'tx-card-expense' } }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByRole('option', { name: '信用卡 · CNY' })
    fireEvent.change(screen.getByLabelText('账户（资产或信用卡）'), { target: { value: 'card' } })
    fireEvent.change(screen.getByLabelText('支出金额'), { target: { value: '300' } })
    fireEvent.change(screen.getByLabelText('分类'), { target: { value: 'cat-food' } })
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))

    await screen.findByText('交易已保存')
    const postCall = apiRequestMock.mock.calls.find(([path]) => path === '/api/v1/transactions')!
    const body = (postCall[1] as { body: Record<string, unknown> }).body
    expect(body).toMatchObject({ type: 'EXPENSE', accountId: 'card', amount: '300.00', currency: 'CNY' })
    expect(body).not.toHaveProperty('entries')
    expect(body).not.toHaveProperty('ledgerAccountId')
  })

  it('负债还款分开提交本金、利息和手续费及对应费用分类', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/accounts')) return { data: accounts, meta: { requestId: 'req-accounts', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      if (path === '/api/v1/transactions') return { data: { id: 'tx-repayment' } }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByText(/工资卡/)
    fireEvent.click(screen.getByRole('tab', { name: '负债还款' }))
    fireEvent.change(screen.getByLabelText('付款账户'), { target: { value: 'account-bank' } })
    fireEvent.change(screen.getByLabelText('负债账户'), { target: { value: 'card' } })
    fireEvent.change(screen.getByLabelText('本金'), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText('利息'), { target: { value: '50' } })
    fireEvent.change(screen.getByLabelText('利息分类'), { target: { value: 'cat-food' } })
    fireEvent.change(screen.getByLabelText('手续费'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('手续费分类'), { target: { value: 'cat-food' } })
    fireEvent.click(screen.getByRole('button', { name: '保存交易' }))

    await screen.findByText('交易已保存')
    const postCall = apiRequestMock.mock.calls.find(([path]) => path === '/api/v1/transactions')!
    const body = (postCall[1] as { body: Record<string, unknown> }).body
    expect(body).toMatchObject({
      type: 'LIABILITY_REPAYMENT', cashAccountId: 'account-bank', liabilityAccountId: 'card', currency: 'CNY',
      principalAmount: '1000.00', interestAmount: '50.00', feeAmount: '10.00',
      interestCategoryId: 'cat-food', feeCategoryId: 'cat-food',
    })
    expect(body).not.toHaveProperty('entries')
  })
})
