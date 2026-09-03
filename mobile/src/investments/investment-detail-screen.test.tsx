import { render, waitFor } from '@testing-library/react-native';

import type { Account, AccountBalance, MobileAccountsApiClient, MobileInvestmentApiClient, Position } from '@/api/api-client';
import { InvestmentDetailScreen } from '@/investments/investment-detail-screen';

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

const balance: AccountBalance = {
  accountId: 'account-1',
  currency: 'CNY',
  ledgerBalance: '1000.00',
  unavailableAmount: '0.00',
  unavailableBreakdown: { frozen: '0.00', inTransit: '0.00', reserved: '0.00' },
  availableBalance: '1000.00',
  liquidityStatus: 'NORMAL',
  asOf: '2026-09-03T00:00:00Z',
  asOfSequence: 0,
};

const positions: Position[] = [
  {
    instrumentId: 'priced-1',
    quantity: '10',
    costBasis: '100.00',
    averageCost: '10.00',
    valuationStatus: 'PRICED',
    marketPrice: '12.00',
    marketValue: '120.00',
    unrealizedProfit: '20.00',
    priceAsOf: '2026-09-02',
  },
  {
    instrumentId: 'unpriced-1',
    quantity: '2',
    costBasis: '30.00',
    averageCost: '15.00',
    valuationStatus: 'UNPRICED',
    marketPrice: null,
    marketValue: null,
    unrealizedProfit: null,
    priceAsOf: null,
  },
];

function createAccountsApi(): jest.Mocked<MobileAccountsApiClient> {
  return {
    listAccounts: jest.fn(),
    getAccount: jest.fn().mockResolvedValue({ data: account, meta: { requestId: 'account-request' } }),
    getAccountBalance: jest.fn().mockResolvedValue({ data: balance, meta: { requestId: 'balance-request' } }),
    getLiabilityDetails: jest.fn(),
    putLiabilityDetails: jest.fn(),
    createAccount: jest.fn(),
    updateAccount: jest.fn(),
    archiveAccount: jest.fn(),
  } as unknown as jest.Mocked<MobileAccountsApiClient>;
}

function createInvestmentApi(): jest.Mocked<MobileInvestmentApiClient> {
  return {
    searchInstruments: jest.fn(),
    createInstrument: jest.fn(),
    getInstrument: jest.fn()
      .mockResolvedValueOnce({ data: { id: 'priced-1', instrumentType: 'STOCK', name: '贵州茅台', market: 'SSE', currency: 'CNY', status: 'ACTIVE', version: 1, sourceMappings: [] }, meta: { requestId: 'instrument-1' } })
      .mockResolvedValueOnce({ data: { id: 'unpriced-1', instrumentType: 'FUND', name: '待估值基金', market: 'MANUAL', currency: 'CNY', status: 'ACTIVE', version: 1, sourceMappings: [{ source: 'MANUAL', externalCode: 'manual-1' }] }, meta: { requestId: 'instrument-2' } }),
    listInstrumentPrices: jest.fn(),
    getMarketDataStatus: jest.fn(),
    listInvestmentTrades: jest.fn(),
    createInvestmentTrade: jest.fn(),
    listInvestmentPositions: jest.fn().mockResolvedValue({ data: positions, meta: { requestId: 'positions-request', hasMore: false, nextCursor: null } }),
    getInvestmentPerformance: jest.fn().mockResolvedValue({
      data: {
        currency: 'CNY',
        realizedProfit: '5.00',
        unrealizedProfit: '20.00',
        dividends: '2.00',
        fees: '1.00',
        taxes: '0.00',
        xirr: null,
        xirrStatus: 'UNPRICED',
      },
      meta: { requestId: 'performance-request' },
    }),
    getInvestmentOverview: jest.fn(),
    getInvestmentReturnCalendar: jest.fn(),
    getInvestmentReturnDayDetails: jest.fn(),
  } as unknown as jest.Mocked<MobileInvestmentApiClient>;
}

describe('Mobile 投资账户详情', () => {
  it('展示服务端持仓和收益，并明确保留未估值字段为空', async () => {
    const api = createInvestmentApi();
    const view = await render(<InvestmentDetailScreen accountId="account-1" api={api} accountsApi={createAccountsApi()} />);

    await waitFor(() => expect(view.getByTestId('investment-detail-positions')).toBeTruthy());
    expect(view.getByText('贵州茅台')).toBeTruthy();
    expect(view.getByText('120.00 CNY')).toBeTruthy();
    expect(view.getByText('待估值基金')).toBeTruthy();
    expect(view.getByText('该持仓缺少有效价格，市值和未实现收益保持为空，未按 0 处理。')).toBeTruthy();
    expect(view.getByText('XIRR 状态：存在未估值')).toBeTruthy();
    expect(view.getByText('XIRR 当前不可用，保留服务端状态，不以 0% 代替。')).toBeTruthy();
    expect(api.listInvestmentPositions).toHaveBeenCalledWith('account-1', { limit: 100 });
    expect(api.getInvestmentPerformance).toHaveBeenCalledWith('account-1');
  });

  it('缺少账户参数时显示可读错误，不请求服务端', async () => {
    const api = createInvestmentApi();
    const accountsApi = createAccountsApi();
    const view = await render(<InvestmentDetailScreen accountId={null} api={api} accountsApi={accountsApi} />);

    await waitFor(() => expect(view.getByTestId('investment-detail-error')).toBeTruthy());
    expect(view.getByText('缺少投资账户 ID。')).toBeTruthy();
    expect(accountsApi.getAccount).not.toHaveBeenCalled();
    expect(api.listInvestmentPositions).not.toHaveBeenCalled();
  });
});
