import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { DashboardPage } from '@/dashboard/DashboardPage'

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

const dashboardWithWarnings = {
  data: {
    baseCurrency: 'CNY',
    asOf: '2026-08-28T00:00:00Z',
    asOfSequence: 12,
    valuationRevision: 1,
    recalculatedAt: '2026-08-28T00:00:00Z',
    projectionStatus: 'CURRENT',
    summary: { totalAssets: '70000.00', availableFunds: '20000.00', investmentAssets: '50000.00', totalLiabilities: '2300.00', netAssets: '67700.00' },
    changeAttribution: { income: '0.00', expense: '0.00', market: '0.00', fx: '0.00', adjustment: '0.00', inclusion: '0.00' },
    distribution: [],
    investmentOverview: { baseCurrency: 'CNY', brokerCash: '50000.00', positionMarketValue: '0.00', totalInvestmentAssets: '50000.00', unpricedInstrumentCount: 0 },
    dataQualityWarnings: [{ code: 'MISSING_EXCHANGE_RATES', affectedCount: 1 }],
  },
  meta: { requestId: 'req-dash' },
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/dashboard']}>
        <QueryClientProvider client={client}>
          <DashboardPage />
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

describe('Dashboard 页面', () => {
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

  it('渲染核心指标、数据质量告警与账户结构', async () => {
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path.includes('/api/v1/dashboard')) return dashboardWithWarnings
      if (path.includes('/api/v1/statistics/assets')) {
        return {
          data: {
            baseCurrency: 'CNY',
            valuationRevision: 1,
            points: [
              { businessDate: '2026-08-27', values: { totalAssets: '69900.00', netAssets: '67600.00' } },
              { businessDate: '2026-08-28', values: { totalAssets: '70000.00', netAssets: '67700.00' } },
            ],
          },
        }
      }
      if (path.includes('/api/v1/statistics/accounts')) {
        return {
          data: {
            baseCurrency: 'CNY',
            valuationRevision: 1,
            points: [{ businessDate: '2026-08-28', values: { 'acc-1': '20000.00', 'acc-2': '50000.00' } }],
          },
        }
      }
      if (path.includes('/api/v1/transactions')) {
        return { data: [{ id: 'tx-1', type: 'EXPENSE', status: 'POSTED', businessDate: '2026-08-28', entries: [] }], meta: {} }
      }
      throw new Error(`unexpected path ${path}`)
    })

    renderPage()
    await screen.findByText('总览')
    expect(screen.getByTestId('metric-总资产').textContent).toContain('70000.00')
    expect(screen.getByTestId('metric-净资产').textContent).toContain('67700.00')
    await screen.findByTestId('quality-warnings')
    expect(screen.getByText(/部分账户缺少汇率/)).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('recent-transactions')).toBeTruthy())
    expect(screen.getAllByText('2026-08-28').length).toBeGreaterThan(0)
  })
})
