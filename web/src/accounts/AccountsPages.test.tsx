import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { CreateAccountPage } from '@/accounts/CreateAccountPage'
import { AccountDetailPage } from '@/accounts/AccountDetailPage'

vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return { ...actual, apiRequest: vi.fn() }
})

import { apiRequest } from '@/lib/api-client'

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

const account = {
  id: 'acc-bank',
  accountClass: 'ASSET',
  accountType: 'BANK',
  name: '工资卡',
  currency: 'CNY',
  status: 'ACTIVE',
  currentUserRole: 'OWNER',
  inclusionRatio: '1.000000',
  version: 2,
}

const cardAccount = {
  id: 'acc-card',
  accountClass: 'LIABILITY',
  accountType: 'CREDIT_CARD',
  name: '信用卡',
  currency: 'CNY',
  status: 'ACTIVE',
  currentUserRole: 'OWNER',
  inclusionRatio: '1.000000',
  version: 2,
}

function renderPage(initialEntries: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={initialEntries}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route path="/accounts/new" element={<CreateAccountPage />} />
            <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

describe('账户创建/编辑/归档', () => {
  beforeEach(() => {
    resetWebSessionForTests()
    setWebSession({ expiresIn: 600, accessToken: 'token-1', session: null })
    setWebUser(user)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    resetWebSessionForTests()
  })

  it('创建账户携带幂等键与期初余额', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === '/api/v1/accounts') {
        return { data: { account, openingTransactionId: 'op-1' } }
      }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage(['/accounts/new'])
    fireEvent.change(screen.getByLabelText('账户名称'), { target: { value: '工资卡' } })
    fireEvent.change(screen.getByLabelText('期初金额'), { target: { value: '20000.00' } })
    fireEvent.click(screen.getByRole('button', { name: '创建账户' }))

    await waitFor(() => expect(apiRequestMock.mock.calls[0][0]).toBe('/api/v1/accounts'))
    const [path, options] = apiRequestMock.mock.calls[0]
    expect(path).toBe('/api/v1/accounts')
    const requestOptions = options as { method?: string; headers?: Record<string, string>; body?: Record<string, unknown> }
    expect(requestOptions.method).toBe('POST')
    expect(requestOptions.headers?.['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/i)
    expect(requestOptions.body?.openingBalance).toEqual({
      amount: '20000.00',
      businessAt: expect.any(String),
      note: null,
    })
  })

  it('编辑账户以 If-Match 提交并在冲突时展示可读文案', async () => {
    let current = account
    let patchSeen = false
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/accounts/acc-bank' && (options?.method ?? 'GET') === 'GET') {
        return { data: current }
      }
      if (path === '/api/v1/accounts/acc-bank' && options?.method === 'PATCH') {
        const etag = new Headers(options?.headers).get('If-Match')
        if (etag !== '"2"') {
          return {
            type: 'https://ziji.app/problems/version-conflict',
            title: 'Conflict',
            status: 409,
            code: 'VERSION_CONFLICT',
            requestId: 'req-1',
            detail: '版本已变化',
          }
        }
        current = { ...account, name: '新名称', version: 3 }
        patchSeen = true
        return { data: current }
      }
      if (path === '/api/v1/accounts/acc-bank/balance') {
        return { data: { accountId: 'acc-bank', currency: 'CNY', ledgerBalance: '20000.00', unavailableAmount: '0.00', unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' }, availableBalance: '20000.00', liquidityStatus: 'NORMAL', asOf: '2026-08-28T00:00:00Z', asOfSequence: 0 } }
      }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/accounts/acc-bank'])
    await screen.findByText('工资卡')
    fireEvent.click(screen.getByRole('button', { name: '编辑账户' }))
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '新名称' } })
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(patchSeen).toBe(true))
    await waitFor(() => expect(screen.getAllByText('新名称').length).toBeGreaterThan(0))
    const patchCall = apiRequestMock.mock.calls.find(([, options]) => (options as RequestInit | undefined)?.method === 'PATCH')
    expect(new Headers((patchCall![1] as RequestInit).headers).get('If-Match')).toBe('"2"')
  })

  it('归档非零余额需要显式确认与原因', async () => {
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/accounts/acc-bank' && (options?.method ?? 'GET') === 'GET') return { data: account }
      if (path === '/api/v1/accounts/acc-bank/balance') {
        return { data: { accountId: 'acc-bank', currency: 'CNY', ledgerBalance: '20000.00', unavailableAmount: '0.00', unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' }, availableBalance: '20000.00', liquidityStatus: 'NORMAL', asOf: '2026-08-28T00:00:00Z', asOfSequence: 0 } }
      }
      if (path === '/api/v1/accounts/acc-bank/archive' && options?.method === 'POST') {
        return { data: { ...account, status: 'ARCHIVED', version: 3 } }
      }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/accounts/acc-bank'])
    await screen.findByText('工资卡')
    fireEvent.click(screen.getByRole('button', { name: '归档账户' }))

    const archiveButton = screen.getByRole('button', { name: '确认归档' }) as HTMLButtonElement
    // 非零余额时确认勾选是必要条件。
    expect(archiveButton.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('归档原因'), { target: { value: '不再使用' } })
    fireEvent.click(screen.getByLabelText('账户余额非零，我确认仍要归档'))
    fireEvent.click(screen.getByRole('button', { name: '确认归档' }))

    await waitFor(() => expect(apiRequestMock.mock.calls.some(([path]) => String(path).includes('/archive'))).toBe(true))
    const archiveCall = apiRequestMock.mock.calls.find(([path]) => String(path).includes('/archive'))
    const body = (archiveCall![1] as { body: { reason: string; confirmNonZeroBalance: boolean } }).body
    expect(body.reason).toBe('不再使用')
    expect(body.confirmNonZeroBalance).toBe(true)
  })

  it('首次维护信用卡负债详情使用 If-None-Match 与完整提醒字段', async () => {
    const emptyDetails = {
      accountId: 'acc-card', interestRate: null, loanDate: null, dueDate: null,
      billingDay: null, repaymentDay: null, currentAmountDue: null, version: 0,
    }
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/accounts/acc-card' && (options?.method ?? 'GET') === 'GET') return { data: cardAccount }
      if (path === '/api/v1/accounts/acc-card/balance') {
        return { data: { accountId: 'acc-card', currency: 'CNY', ledgerBalance: '-2300.00', unavailableAmount: '0.00', unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' }, availableBalance: '-2300.00', liquidityStatus: 'NEGATIVE_AVAILABLE', asOf: '2026-08-28T00:00:00Z', asOfSequence: 0 } }
      }
      if (path === '/api/v1/accounts/acc-card/liability-details' && (options?.method ?? 'GET') === 'GET') return { data: emptyDetails }
      if (path === '/api/v1/accounts/acc-card/liability-details' && options?.method === 'PUT') {
        return { data: { ...emptyDetails, interestRate: '0.045', billingDay: 8, repaymentDay: 20, currentAmountDue: '1000.00', version: 1 } }
      }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/accounts/acc-card'])
    fireEvent.click(await screen.findByRole('button', { name: '维护负债详情' }))
    fireEvent.change(screen.getByLabelText('年利率（年化比例）'), { target: { value: '0.045' } })
    fireEvent.change(screen.getByLabelText('账单日'), { target: { value: '8' } })
    fireEvent.change(screen.getByLabelText('还款日'), { target: { value: '20' } })
    fireEvent.change(screen.getByLabelText('本期应还金额'), { target: { value: '1000' } })
    fireEvent.click(screen.getByRole('button', { name: '保存负债详情' }))

    await waitFor(() => expect(apiRequestMock.mock.calls.some(([, options]) => (options as RequestInit | undefined)?.method === 'PUT')).toBe(true))
    const putCall = apiRequestMock.mock.calls.find(([, options]) => (options as RequestInit | undefined)?.method === 'PUT')!
    const options = putCall[1] as { headers: Record<string, string>; body: Record<string, unknown> }
    expect(putCall[0]).toBe('/api/v1/accounts/acc-card/liability-details')
    expect(options.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/i)
    expect(options.headers['If-None-Match']).toBe('*')
    expect(options.headers['If-Match']).toBeUndefined()
    expect(options.body).toEqual({
      interestRate: '0.045', loanDate: null, dueDate: null,
      billingDay: 8, repaymentDay: 20, currentAmountDue: '1000.00',
    })
  })

  it('已有负债详情使用其独立版本作为 If-Match', async () => {
    const details = {
      accountId: 'acc-card', interestRate: '0.045', loanDate: null, dueDate: null,
      billingDay: 8, repaymentDay: 20, currentAmountDue: '1000.00', version: 3,
    }
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/accounts/acc-card' && (options?.method ?? 'GET') === 'GET') return { data: cardAccount }
      if (path === '/api/v1/accounts/acc-card/balance') {
        return { data: { accountId: 'acc-card', currency: 'CNY', ledgerBalance: '-2300.00', unavailableAmount: '0.00', unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' }, availableBalance: '-2300.00', liquidityStatus: 'NEGATIVE_AVAILABLE', asOf: '2026-08-28T00:00:00Z', asOfSequence: 0 } }
      }
      if (path === '/api/v1/accounts/acc-card/liability-details' && (options?.method ?? 'GET') === 'GET') return { data: details }
      if (path === '/api/v1/accounts/acc-card/liability-details' && options?.method === 'PUT') return { data: { ...details, currentAmountDue: '1200.00', version: 4 } }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/accounts/acc-card'])
    fireEvent.click(await screen.findByRole('button', { name: '维护负债详情' }))
    fireEvent.change(screen.getByLabelText('本期应还金额'), { target: { value: '1200' } })
    fireEvent.click(screen.getByRole('button', { name: '保存负债详情' }))

    await waitFor(() => expect(apiRequestMock.mock.calls.some(([, options]) => (options as RequestInit | undefined)?.method === 'PUT')).toBe(true))
    const putCall = apiRequestMock.mock.calls.find(([, options]) => (options as RequestInit | undefined)?.method === 'PUT')!
    expect(new Headers((putCall[1] as RequestInit).headers).get('If-Match')).toBe('"3"')
  })
})
