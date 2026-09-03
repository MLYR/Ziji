import { createMobileInvestmentApiClient, type CreateInvestmentTradeRequest } from '@/api/api-client';

describe('Mobile 投资 API 客户端', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('使用 Bearer 封装投资概览、行情、持仓和收益月历 endpoints', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async () => (
      new Response(JSON.stringify({ data: [], meta: { requestId: 'request-1', hasMore: false, nextCursor: null } }), { status: 200 })
    ));
    const client = createMobileInvestmentApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.getInvestmentOverview();
    await client.getMarketDataStatus();
    await client.listInvestmentPositions('account / id', { asOf: '2026-09-03T00:00:00Z', limit: 100 });
    await client.getInvestmentReturnCalendar('2026-08', 'PORTFOLIO');
    await client.getInvestmentReturnDayDetails('2026-08-03', 'INSTRUMENT', 'instrument / id');

    expect(fetchMock.mock.calls.map(([url]) => url.toString())).toEqual([
      'https://api.ziji.test/api/v1/investments/overview',
      'https://api.ziji.test/api/v1/market-data/status',
      'https://api.ziji.test/api/v1/investment-accounts/account%20%2F%20id/positions?asOf=2026-09-03T00%3A00%3A00Z&limit=100',
      'https://api.ziji.test/api/v1/investment-returns/calendar?month=2026-08&scopeType=PORTFOLIO',
      'https://api.ziji.test/api/v1/investment-returns/calendar/2026-08-03/details?scopeType=INSTRUMENT&instrumentId=instrument+%2F+id',
    ]);
    expect(fetchMock.mock.calls.every(([, init]) => init?.credentials === 'omit')).toBe(true);
    expect(fetchMock.mock.calls.every(([, init]) => new Headers(init?.headers).get('Authorization') === 'Bearer access-test')).toBe(true);
  });

  it('搜索产品并提交投资交易时使用生成请求类型和幂等键', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async () => (
      new Response(JSON.stringify({ data: [], meta: { requestId: 'request-1', hasMore: false, nextCursor: null } }), { status: 201 })
    ));
    const client = createMobileInvestmentApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => null });
    const body: CreateInvestmentTradeRequest = {
      side: 'BUY',
      investmentAccountId: 'account-1',
      instrumentId: 'instrument-1',
      currency: 'CNY',
      quantity: '10',
      unitPrice: '12.30',
      feeAmount: '1.00',
      taxAmount: '0.00',
      tradeAt: '2026-09-03T12:00:00+08:00',
    };

    await client.searchInstruments('沪深 300', 20);
    await client.createInvestmentTrade('investment-trade-key', body);

    expect(fetchMock.mock.calls[0]?.[0].toString()).toBe('https://api.ziji.test/api/v1/instruments/search?q=%E6%B2%AA%E6%B7%B1+300&limit=20');
    expect(fetchMock.mock.calls[1]?.[0].toString()).toBe('https://api.ziji.test/api/v1/investment-trades');
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('investment-trade-key');
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(JSON.stringify(body));
  });
});
