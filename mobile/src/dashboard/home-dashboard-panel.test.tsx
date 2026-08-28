import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import { HomeDashboardPanel } from '@/dashboard/home-dashboard-panel';
import { mobileDashboardApiClient, mobileTransactionApiClient } from '@/auth/default-auth-session';

jest.mock('@/auth/default-auth-session', () => ({
  mobileDashboardApiClient: { getDashboard: jest.fn(), getAssetStatistics: jest.fn() },
  mobileTransactionApiClient: { listTransactions: jest.fn() },
}));

const dashboard = {
  data: {
    baseCurrency: 'CNY',
    asOf: '2026-08-28T00:00:00Z',
    asOfSequence: 12,
    valuationRevision: 1,
    recalculatedAt: '2026-08-28T00:00:00Z',
    projectionStatus: 'CURRENT' as const,
    summary: { totalAssets: '70000.00', availableFunds: '20000.00', investmentAssets: '50000.00', totalLiabilities: '2300.00', netAssets: '67700.00' },
    changeAttribution: { income: '0.00', expense: '0.00', market: '0.00', fx: '0.00', adjustment: '0.00', inclusion: '0.00' },
    distribution: [],
    investmentOverview: { baseCurrency: 'CNY', brokerCash: '50000.00', positionMarketValue: '0.00', totalInvestmentAssets: '50000.00', unpricedInstrumentCount: 0 },
    dataQualityWarnings: [{ code: 'MISSING_EXCHANGE_RATES', affectedCount: 1 }],
  },
  meta: { requestId: 'req-dash' },
};

describe('首页核心指标面板', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('渲染五个核心指标、质量告警、趋势摘要与近期流水', async () => {
    (mobileDashboardApiClient.getDashboard as jest.Mock).mockResolvedValue(dashboard);
    (mobileDashboardApiClient.getAssetStatistics as jest.Mock).mockResolvedValue({
      data: {
        baseCurrency: 'CNY',
        valuationRevision: 1,
        points: [
          { businessDate: '2026-08-27', values: { totalAssets: '69900.00', netAssets: '67600.00' } },
          { businessDate: '2026-08-28', values: { totalAssets: '70000.00', netAssets: '67700.00' } },
        ],
      },
    });
    (mobileTransactionApiClient.listTransactions as jest.Mock).mockResolvedValue({
      data: [{ id: 'tx-1', type: 'EXPENSE', status: 'POSTED', businessDate: '2026-08-28' }],
    });

    const view = await render(<HomeDashboardPanel onOpenQuickRecord={() => undefined} />);
    await waitFor(() => expect(view.getByTestId('home-dashboard')).toBeTruthy());
    expect(view.getByText('67700.00 CNY')).toBeTruthy();
    expect(view.getByText('部分账户缺少汇率，未计入折算总额（1 项未计入）')).toBeTruthy();
    expect(view.getByTestId('home-trend-summary').props.children).toContain('67600.00 → 67700.00');
    expect(view.getByText(new RegExp('支出'))).toBeTruthy();
    expect(mobileDashboardApiClient.getDashboard).toHaveBeenCalledTimes(1);
  });

  it('服务端不可用时显示错误与记账入口，不伪造指标', async () => {
    (mobileDashboardApiClient.getDashboard as jest.Mock).mockRejectedValue(new Error('network down'));

    const view = await render(<HomeDashboardPanel onOpenQuickRecord={jest.fn()} />);

    await waitFor(() => expect(view.getByTestId('home-dashboard-error')).toBeTruthy());
    expect(view.getByText('无法加载总览：网络或服务暂不可用。')).toBeTruthy();
    expect(view.queryByTestId('metric-净资产')).toBeNull();

    act(() => {
      fireEvent.press(view.getByTestId('home-open-quick-record'));
    });
  });
});
