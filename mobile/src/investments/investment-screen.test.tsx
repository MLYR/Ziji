import { fireEvent, render, userEvent, waitFor } from '@testing-library/react-native';

import type {
  Account,
  InvestmentOverviewEnvelope,
  MarketDataStatusEnvelope,
  MobileAccountsApiClient,
  MobileInvestmentApiClient,
} from '@/api/api-client';
import { InvestmentScreen } from '@/investments/investment-screen';

const account: Account = {
  id: 'account-1',
  name: '华泰证券',
  accountClass: 'INVESTMENT',
  accountType: 'BROKERAGE',
  currency: 'CNY',
  institution: '华泰',
  status: 'ACTIVE',
  currentUserRole: 'OWNER',
  inclusionRatio: '1',
  version: 1,
};

const overview: InvestmentOverviewEnvelope = {
  data: {
    baseCurrency: 'CNY',
    brokerCash: '10000.00',
    positionMarketValue: '25000.00',
    totalInvestmentAssets: '35000.00',
    unpricedInstrumentCount: 2,
  },
  meta: { requestId: 'overview-request' },
};

const marketStatus: MarketDataStatusEnvelope = {
  data: {
    source: 'TUSHARE',
    status: 'DEGRADED',
    lastSuccessfulSyncAt: '2026-09-03T06:00:00Z',
    freshness: 'STALE',
  },
  meta: { requestId: 'market-request' },
};

const instrument = {
  id: 'instrument-1',
  instrumentType: 'ETF' as const,
  name: '沪深300ETF',
  market: 'SSE',
  currency: 'CNY',
  status: 'ACTIVE' as const,
  version: 1,
  sourceMappings: [{ source: 'TUSHARE' as const, externalCode: '510300.SZ', sourceMarket: 'SZ' }],
};

const portfolioCalendar = {
  data: {
    scopeType: 'PORTFOLIO' as const,
    instrumentId: null,
    baseCurrency: 'CNY',
    month: '2026-08',
    valuationRevision: 4,
    asOf: '2026-09-01T00:00:00Z',
    recalculatedAt: '2026-09-01T01:00:00Z',
    summaryStatus: 'PARTIAL' as const,
    monthlyProfit: null,
    monthlyReturnRate: null,
    profitDayCount: 1,
    lossDayCount: 0,
    zeroDayCount: 1,
    days: [
      { businessDate: '2026-08-01', status: 'NON_TRADING_DAY' as const, dailyProfit: null, dailyReturnRate: null, missingInstrumentCount: 0 },
      { businessDate: '2026-08-03', status: 'CALCULATED' as const, dailyProfit: '10.00', dailyReturnRate: '0.20', missingInstrumentCount: 0 },
      { businessDate: '2026-08-04', status: 'PARTIAL' as const, dailyProfit: null, dailyReturnRate: null, missingInstrumentCount: 1 },
    ],
    dataQualityWarnings: [{ code: 'MISSING_PRICES', affectedCount: 1 }],
  },
  meta: { requestId: 'calendar-request' },
};

const dayDetails = {
  data: {
    scopeType: 'PORTFOLIO' as const,
    instrumentId: null,
    businessDate: '2026-08-03',
    baseCurrency: 'CNY',
    valuationRevision: 4,
    asOf: '2026-09-01T00:00:00Z',
    status: 'CALCULATED' as const,
    beginValue: '100.00',
    endValue: '110.00',
    netCashFlow: '0.00',
    dailyProfit: '10.00',
    dailyReturnRate: '0.20',
    marketEffect: '10.00',
    fxEffect: '0.00',
    dividends: '0.00',
    fees: '0.00',
    taxes: '0.00',
    contributions: [{
      contributionType: 'INSTRUMENT' as const,
      instrumentId: 'instrument-1',
      label: '沪深300ETF',
      profit: '10.00',
      returnRate: '0.20',
      status: 'CALCULATED' as const,
      priceAsOf: '2026-08-03',
    }],
    dataQualityWarnings: [],
  },
  meta: { requestId: 'day-details-request' },
};

function createApi(): jest.Mocked<MobileInvestmentApiClient> {
  return {
    searchInstruments: jest.fn().mockResolvedValue({ data: [instrument], meta: { requestId: 'search-request', hasMore: false, nextCursor: null } }),
    createInstrument: jest.fn().mockResolvedValue({ data: instrument, meta: { requestId: 'create-instrument-request' } }),
    getInstrument: jest.fn(),
    listInstrumentPrices: jest.fn(),
    getMarketDataStatus: jest.fn().mockResolvedValue(marketStatus),
    listInvestmentTrades: jest.fn(),
    createInvestmentTrade: jest.fn().mockResolvedValue({ data: {}, meta: { requestId: 'trade-request' } }),
    listInvestmentPositions: jest.fn(),
    getInvestmentPerformance: jest.fn(),
    getInvestmentOverview: jest.fn().mockResolvedValue(overview),
    getInvestmentReturnCalendar: jest.fn().mockResolvedValue(portfolioCalendar),
    getInvestmentReturnDayDetails: jest.fn().mockResolvedValue(dayDetails),
  } as unknown as jest.Mocked<MobileInvestmentApiClient>;
}

function createAccountsApi(): Pick<MobileAccountsApiClient, 'listAccounts'> {
  return {
    listAccounts: jest.fn().mockResolvedValue({ data: [account], meta: { requestId: 'accounts-request', hasMore: false, nextCursor: null } }),
  };
}

describe('Mobile 投资主页面', () => {
  it('展示服务端投资概览、行情质量、未估值提示和账户入口', async () => {
    const api = createApi();
    const view = await render(<InvestmentScreen api={api} accountsApi={createAccountsApi()} onOpenAccount={jest.fn()} initialMonth="2026-08" />);

    await waitFor(() => expect(view.getByTestId('investment-overview')).toBeTruthy());
    expect(view.getByText('35000.00 CNY')).toBeTruthy();
    expect(view.getByText('有 2 个标的缺少有效价格，未估值字段保持为空，请打开持仓详情处理。')).toBeTruthy();
    expect(view.getByText('状态：降级 · 新鲜度：已过期')).toBeTruthy();
    expect(view.getByText('来源：Tushare Pro（盘后行情 / 最新公布净值）')).toBeTruthy();
    expect(view.getByText('华泰证券 · CNY')).toBeTruthy();
    // “部分估值”同时出现在日历状态与汇总语义中，按日期定位避免测试依赖重复文本。
    expect(view.getByTestId('investment-return-day-2026-08-04').props.accessibilityLabel).toContain('部分估值');
    expect(view.getByTestId('investment-return-day-2026-08-03').props.accessibilityLabel).toContain('已计算');
  });

  it('搜索产品后分别提交买入、卖出和分红，载荷交给服务端而不计算成交金额', async () => {
    const user = userEvent.setup();
    const api = createApi();
    const view = await render(<InvestmentScreen api={api} accountsApi={createAccountsApi()} onOpenAccount={jest.fn()} initialMonth="2026-08" />);

    await waitFor(() => expect(view.getByTestId('investment-overview')).toBeTruthy());
    // 输入框与提交按钮共享可见文案，使用输入框 testID 保持交互目标唯一。
    await user.type(view.getByTestId('investment-product-search-input'), '510300');
    await user.press(view.getByTestId('investment-product-search-submit'));
    await waitFor(() => expect(view.getByTestId('investment-product-instrument-1')).toBeTruthy());
    await user.press(view.getByTestId('investment-product-instrument-1'));
    await user.press(view.getByTestId('investment-trade-account-account-1'));
    await user.type(view.getByTestId('investment-trade-quantity'), '10');
    await user.type(view.getByTestId('investment-trade-unit-price'), '12.30');
    await user.press(view.getByTestId('investment-trade-submit'));

    await waitFor(() => expect(api.createInvestmentTrade).toHaveBeenCalledTimes(1));
    const buyBody = api.createInvestmentTrade.mock.calls[0]?.[1];
    expect(buyBody).toMatchObject({
      side: 'BUY',
      investmentAccountId: 'account-1',
      instrumentId: 'instrument-1',
      quantity: '10',
      unitPrice: '12.30',
      feeAmount: '0.00',
      taxAmount: '0.00',
    });
    expect(buyBody).not.toHaveProperty('grossAmount');

    await user.press(view.getByTestId('investment-trade-side-SELL'));
    await user.type(view.getByTestId('investment-trade-quantity'), '2');
    await user.type(view.getByTestId('investment-trade-unit-price'), '13.00');
    await user.press(view.getByTestId('investment-trade-submit'));
    await waitFor(() => expect(api.createInvestmentTrade).toHaveBeenCalledTimes(2));
    expect(api.createInvestmentTrade.mock.calls[1]?.[1]).toMatchObject({ side: 'SELL', quantity: '2', unitPrice: '13.00' });

    await user.press(view.getByTestId('investment-trade-side-DIVIDEND'));
    await user.type(view.getByTestId('investment-trade-dividend'), '8.00');
    await user.press(view.getByTestId('investment-trade-submit'));
    await waitFor(() => expect(api.createInvestmentTrade).toHaveBeenCalledTimes(3));
    const dividendBody = api.createInvestmentTrade.mock.calls[2]?.[1];
    expect(dividendBody).toMatchObject({ side: 'DIVIDEND', dividendAmount: '8.00' });
    expect(dividendBody).not.toHaveProperty('quantity');
    expect(dividendBody).not.toHaveProperty('unitPrice');
  });

  it('日期状态始终以文字展示，打开日期后通过底部明细读取服务端归因', async () => {
    const user = userEvent.setup();
    const api = createApi();
    const view = await render(<InvestmentScreen api={api} accountsApi={createAccountsApi()} onOpenAccount={jest.fn()} initialMonth="2026-08" />);

    await waitFor(() => expect(view.getByTestId('investment-return-day-2026-08-03')).toBeTruthy());
    expect(view.getByTestId('investment-return-day-2026-08-01').props.accessibilityLabel).toContain('非交易日');
    expect(view.getByTestId('investment-return-day-2026-08-04').props.accessibilityLabel).toContain('部分估值');

    await user.press(view.getByTestId('investment-return-day-2026-08-03'));
    await waitFor(() => expect(view.getByTestId('investment-return-day-sheet')).toBeTruthy());
    expect(api.getInvestmentReturnDayDetails).toHaveBeenCalledWith('2026-08-03', 'PORTFOLIO', undefined);
    expect(view.getByText('当日收益 10.00 CNY · 收益率 0.20%')).toBeTruthy();
    expect(view.getByText('沪深300ETF')).toBeTruthy();
  });

  it('选择单一标的后带 instrumentId 请求月历', async () => {
    const user = userEvent.setup();
    const api = createApi();
    const view = await render(<InvestmentScreen api={api} accountsApi={createAccountsApi()} onOpenAccount={jest.fn()} initialMonth="2026-08" />);

    // 输入框与提交按钮共享可见文案，使用输入框 testID 保持交互目标唯一。
    await user.type(view.getByTestId('investment-product-search-input'), '510300');
    await user.press(view.getByTestId('investment-product-search-submit'));
    await waitFor(() => expect(view.getByTestId('investment-product-instrument-1')).toBeTruthy());
    await user.press(view.getByTestId('investment-product-instrument-1'));
    await user.press(view.getByTestId('investment-calendar-scope-instrument'));

    await waitFor(() => expect(api.getInvestmentReturnCalendar).toHaveBeenCalledWith('2026-08', 'INSTRUMENT', 'instrument-1'));
    expect(view.getByText('沪深300ETF · SSE')).toBeTruthy();
  });
});
