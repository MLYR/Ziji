import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { AccountsPage } from '@/accounts/AccountsPage'

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

const accounts = [
  { id: 'acc-bank', accountClass: 'ASSET', accountType: 'BANK', name: '工资卡', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 },
  { id: 'acc-fund', accountClass: 'INVESTMENT', accountType: 'FUND', name: '券商现金', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 },
  { id: 'acc-card', accountClass: 'LIABILITY', accountType: 'CREDIT_CARD', name: '信用卡', currency: 'CNY', status: 'ACTIVE', currentUserRole: 'OWNER', inclusionRatio: '1.000000', version: 1 },
]

function balance(accountId: string, ledger: string, available: string, status: 'NORMAL' | 'NEGATIVE_AVAILABLE') {
  return {
    data: {
      accountId,
      currency: 'CNY',
      ledgerBalance: ledger,
      unavailableAmount: '0.00',
      unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' },
      availableBalance: available,
      liquidityStatus: status,
      asOf: '2026-08-28T00:00:00Z',
      asOfSequence: 0,
    },
  }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/accounts']}>
        <QueryClientProvider client={client}>
          <AccountsPage />
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

describe('账户列表页', () => {
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

  it('按资产/投资/负债分组渲染账户并展示余额状态', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === '/api/v1/accounts?limit=100') return { data: accounts, meta: { requestId: 'req', nextCursor: null, hasMore: false } }
      if (path === '/api/v1/accounts/acc-bank/balance') return balance('acc-bank', '20000.00', '20000.00', 'NORMAL')
      if (path === '/api/v1/accounts/acc-fund/balance') return balance('acc-fund', '50000.00', '50000.00', 'NORMAL')
      if (path === '/api/v1/accounts/acc-card/balance') return balance('acc-card', '-2300.00', '-2300.00', 'NEGATIVE_AVAILABLE')
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByText('工资卡')
    expect(screen.getByText('资产账户')).toBeTruthy()
    expect(screen.getByText('投资账户')).toBeTruthy()
    expect(screen.getByText('负债账户')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('balance-acc-bank').textContent).toContain('20000.00'))
    expect(screen.getByTestId('balance-acc-card').textContent).toContain('-2300.00')
    // 负可用余额不只靠颜色表达，附文字提示。
    expect(screen.getAllByText('⚠').length).toBeGreaterThan(0)
  })

  it('无账户时显示空态与创建入口', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === '/api/v1/accounts?limit=100') return { data: [], meta: { requestId: 'req', nextCursor: null, hasMore: false } }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByText('还没有账户')
    expect(screen.getByRole('link', { name: /创建账户/ })).toBeTruthy()
  })

  it('加载失败显示错误与重试按钮', async () => {
    apiRequestMock.mockImplementation(async () => {
      throw new Error('network down')
    })

    renderPage()
    await screen.findByText('无法加载账户')
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy()
  })
})
