import { render, userEvent, waitFor } from '@testing-library/react-native';

import { TransactionsScreen } from '@/ledger/transactions-screen';

const transactions = [
  { id: 'tx-2', type: 'EXPENSE', status: 'POSTED' as const, businessDate: '2026-08-28', version: 1 },
  { id: 'tx-1', type: 'INCOME', status: 'REVERSED' as const, businessDate: '2026-08-27', version: 2 },
];

describe('Mobile 流水列表', () => {
  it('初次加载展示列表和游标分页，下一页追加结果', async () => {
    const listTransactions = jest.fn()
      .mockResolvedValueOnce({ data: transactions, meta: { nextCursor: 'cursor-1', hasMore: true } })
      .mockResolvedValueOnce({ data: [{ id: 'tx-3', type: 'REFUND', status: 'POSTED' as const, businessDate: '2026-08-26', version: 1 }], meta: { nextCursor: null, hasMore: false } });
    const onViewTransaction = jest.fn();
    const user = userEvent.setup();
    const view = await render(
      <TransactionsScreen listTransactions={listTransactions} onViewTransaction={onViewTransaction} onOpenQuickRecord={jest.fn()} />,
    );

    await waitFor(() => expect(view.getByTestId('transaction-row-tx-2')).toBeTruthy());
    expect(listTransactions).toHaveBeenCalledWith(50, {});
    await user.press(view.getByTestId('transactions-load-next'));
    await view.findByTestId('transaction-row-tx-3');
    expect(listTransactions).toHaveBeenLastCalledWith(50, { cursor: 'cursor-1' });
    expect(view.getByTestId('transaction-row-tx-3')).toBeTruthy();
  });

  it('应用筛选后以原始条件重新查询，并可打开详情', async () => {
    const listTransactions = jest.fn().mockResolvedValue({ data: transactions, meta: { nextCursor: null, hasMore: false } });
    const onViewTransaction = jest.fn();
    const user = userEvent.setup();
    const view = await render(
      <TransactionsScreen listTransactions={listTransactions} onViewTransaction={onViewTransaction} onOpenQuickRecord={jest.fn()} />,
    );
    await waitFor(() => expect(view.getByTestId('transactions-screen')).toBeTruthy());
    await user.type(view.getByTestId('transactions-filter-account'), 'account-1');
    await user.type(view.getByTestId('transactions-filter-date-from'), '2026-08-01');
    await user.type(view.getByTestId('transactions-filter-date-to'), '2026-08-31');
    await user.press(view.getByTestId('transactions-filter-type-EXPENSE'));
    await user.press(view.getByTestId('transactions-apply-filters'));

    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith(50, {
      accountId: 'account-1', type: 'EXPENSE', dateFrom: '2026-08-01', dateTo: '2026-08-31',
    }));
    await user.press(view.getByTestId('transaction-row-tx-1'));
    expect(onViewTransaction).toHaveBeenCalledWith('tx-1');
  });

  it('服务端失败显示错误，不伪造流水', async () => {
    const listTransactions = jest.fn().mockRejectedValue(new Error('network down'));
    const user = userEvent.setup();
    const view = await render(
      <TransactionsScreen listTransactions={listTransactions} onViewTransaction={jest.fn()} onOpenQuickRecord={jest.fn()} />,
    );
    await waitFor(() => expect(view.getByTestId('transactions-message')).toBeTruthy());
    expect(view.queryByTestId('transaction-row-tx-1')).toBeNull();
  });
});
