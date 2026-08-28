import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { TransactionDetailPage } from '@/ledger/TransactionDetailPage'
import { TransactionsPage } from '@/ledger/TransactionsPage'

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

const account = { id: '0191c1a1-0000-7000-8000-000000000001', accountClass: 'ASSET', accountType: 'BANK', name: '工资卡', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 }

const postedTransaction = {
  id: 'tx-1',
  type: 'EXPENSE',
  status: 'POSTED',
  businessAt: '2026-08-18T02:00:00Z',
  businessDate: '2026-08-18',
  timezone: 'Asia/Shanghai',
  source: 'MANUAL',
  rootTransactionId: 'tx-1',
  versionNo: 1,
  version: 1,
  entries: [
    { id: 'e1', ledgerAccountId: 'ledger-1', sequenceNo: 1, direction: 'D', amount: '12.50', currency: 'CNY', businessDate: '2026-08-18' },
    { id: 'e2', ledgerAccountId: 'ledger-2', sequenceNo: 2, direction: 'C', amount: '12.50', currency: 'CNY', businessDate: '2026-08-18' },
  ],
}

function renderPage(initialEntries: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={initialEntries}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route path="/transactions" element={<TransactionsPage />} />
            <Route path="/transactions/:transactionId" element={<TransactionDetailPage />} />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

describe('流水列表与详情', () => {
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

  it('列表按筛选查询并支持加载更多', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === '/api/v1/transactions?limit=50') return { data: [postedTransaction], meta: { requestId: 'req', nextCursor: 'cursor-1', hasMore: true } }
      if (path === '/api/v1/transactions?limit=50&type=EXPENSE') {
        return { data: [postedTransaction], meta: { requestId: 'req', nextCursor: null, hasMore: false } }
      }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage(['/transactions'])
    await screen.findByText('2026-08-18 · 支出')
    expect(screen.getByText('加载更多')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('类型'), { target: { value: 'EXPENSE' } })
    await waitFor(() => {
      expect(apiRequestMock.mock.calls.some(([path]) => String(path).includes('type=EXPENSE'))).toBe(true)
    })
  })

  it('分类 ID 输入作为筛选条件传递', async () => {
    apiRequestMock.mockImplementation(async () => ({
      data: [postedTransaction],
      meta: { requestId: 'req', nextCursor: null, hasMore: false },
    }))

    renderPage(['/transactions'])
    await screen.findByText('2026-08-18 · 支出')
    fireEvent.change(screen.getByLabelText('分类 ID'), {
      target: { value: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa' },
    })

    await waitFor(() => {
      expect(apiRequestMock.mock.calls.some(([path]) => String(path).includes('categoryId='))).toBe(true)
    })
  })

  it('详情页展示分录并支持作废确认', async () => {
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/transactions/tx-1' && (options?.method ?? 'GET') === 'GET') return { data: postedTransaction }
      if (path === '/api/v1/transactions/tx-1/reversal' && options?.method === 'POST') {
        return { data: { ...postedTransaction, status: 'REVERSED', version: 2 } }
      }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/transactions/tx-1'])
    await screen.findByText('#1 · 借')
    expect(screen.getByText('#2 · 贷')).toBeTruthy()
    expect(screen.getAllByText(/12.50/).length).toBe(2)

    fireEvent.click(screen.getByRole('button', { name: '作废此交易' }))
    const confirmButton = screen.getByRole('button', { name: '确认作废' }) as HTMLButtonElement
    expect(confirmButton.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('作废原因'), { target: { value: '重复记账' } })
    fireEvent.click(screen.getByRole('button', { name: '确认作废' }))

    await waitFor(() => {
      const reversalCall = apiRequestMock.mock.calls.find(([path]) => String(path).includes('/reversal'))
      expect(reversalCall).toBeTruthy()
      const headers = new Headers((reversalCall![1] as RequestInit).headers)
      expect(headers.get('If-Match')).toBe('"1"')
      expect(headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i)
    })
  })

  it('修订要求完整替换字段并以 If-Match 提交', async () => {
    apiRequestMock.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/v1/transactions/tx-1' && (options?.method ?? 'GET') === 'GET') return { data: postedTransaction }
      if (path.startsWith('/api/v1/accounts')) return { data: [account], meta: { requestId: 'req', nextCursor: null, hasMore: false } }
      if (path === '/api/v1/transactions/tx-1/revisions' && options?.method === 'POST') {
        return { data: { ...postedTransaction, version: 2 } }
      }
      throw new Error(`unexpected path ${path} ${options?.method}`)
    })

    renderPage(['/transactions/tx-1'])
    await screen.findByText('修改此交易')
    fireEvent.click(screen.getByRole('button', { name: '修改此交易' }))

    // 缺失字段时阻断提交。
    fireEvent.click(screen.getByRole('button', { name: '提交修改' }))
    expect(screen.getAllByText('请选择账户').length).toBeGreaterThan(0)

    // 等待账户选项加载后再选择，避免 select 无匹配项。
    await screen.findByText(/工资卡/)
    const select = screen.getByLabelText('账户') as HTMLSelectElement
    fireEvent.change(select, { target: { value: '0191c1a1-0000-7000-8000-000000000001' } })
    fireEvent.change(screen.getByLabelText(/金额/), { target: { value: '20.00' } })
    fireEvent.change(screen.getByLabelText('分类 ID'), { target: { value: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa' } })
    fireEvent.change(screen.getByLabelText('修改原因'), { target: { value: '金额录错' } })
    fireEvent.click(screen.getByRole('button', { name: '提交修改' }))

    await waitFor(() => {
      const reviseCall = apiRequestMock.mock.calls.find(([path]) => String(path).includes('/revisions'))
      expect(reviseCall).toBeTruthy()
      const headers = new Headers((reviseCall![1] as RequestInit).headers)
      expect(headers.get('If-Match')).toBe('"1"')
      const body = (reviseCall![1] as { body: { reason: string; replacement: { amount: string } } }).body
      const typedBody = body satisfies {
        reason: string
        replacement: { amount: string }
      }
      expect(typedBody.replacement.amount).toBe('20.00')
      expect(typedBody.reason).toBe('金额录错')
    })
  })
})
