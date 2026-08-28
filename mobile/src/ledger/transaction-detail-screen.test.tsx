import { render, userEvent, waitFor } from '@testing-library/react-native';

import { TransactionDetailScreen } from '@/ledger/transaction-detail-screen';

const transaction = {
  id: 'tx-1',
  type: 'EXPENSE',
  status: 'POSTED' as const,
  businessAt: '2026-08-28T10:00:00Z',
  businessDate: '2026-08-28',
  timezone: 'Asia/Shanghai',
  version: 1,
  entries: [{ id: 'entry-1', amount: '10.00', currency: 'CNY' as const, direction: 'D' as const }],
};

describe('Mobile 交易详情', () => {
  it('修改 POSTED 收支时提交替换命令、ETag 与稳定幂等键', async () => {
    const replacement = { type: 'EXPENSE' as const, accountId: '11111111-1111-4111-8111-111111111111', amount: '12.50', currency: 'CNY' as const, categoryId: '22222222-2222-4222-8222-222222222222', businessAt: '2026-08-28T10:00:00Z', timezone: 'Asia/Shanghai', note: null };
    const getTransaction = jest.fn().mockResolvedValue({ data: transaction, meta: { requestId: 'req-1' } });
    const reviseTransaction = jest.fn().mockResolvedValue({ data: { ...transaction, id: 'tx-new' }, meta: { requestId: 'req-2' } });
    const reverseTransaction = jest.fn();
    const user = userEvent.setup();
    const view = await render(
      <TransactionDetailScreen transactionId="tx-1" getTransaction={getTransaction} reviseTransaction={reviseTransaction} reverseTransaction={reverseTransaction} />,
    );
    await view.findByTestId('transaction-revision-open');
    await user.press(view.getByTestId('transaction-revision-open'));
    await view.findByTestId('transaction-revision-reason');
    await user.type(view.getByTestId('transaction-revision-reason'), '金额输错了');
    await user.type(view.getByTestId('transaction-revision-account'), '11111111-1111-4111-8111-111111111111');
    await user.type(view.getByTestId('transaction-revision-amount'), '12.50');
    await user.type(view.getByTestId('transaction-revision-category'), '22222222-2222-4222-8222-222222222222');
    await user.press(view.getByTestId('transaction-revision-submit'));

    await waitFor(() => expect(reviseTransaction).toHaveBeenCalledTimes(1));
    expect(reviseTransaction.mock.calls[0]).toEqual(['tx-1', '"1"', expect.any(String), { reason: '金额输错了', replacement }]);
    expect(view.getByTestId('transaction-detail-message').props.children).toBe('修改成功：已生成替代交易。');
  });

  it('作废需要原因，提交冲正命令并保留原交易说明', async () => {
    const getTransaction = jest.fn().mockResolvedValue({ data: transaction, meta: { requestId: 'req-1' } });
    const reviseTransaction = jest.fn();
    const reverseTransaction = jest.fn().mockResolvedValue({ data: { ...transaction, type: 'REVERSAL', status: 'REVERSED' }, meta: { requestId: 'req-2' } });
    const user = userEvent.setup();
    const view = await render(
      <TransactionDetailScreen transactionId="tx-1" getTransaction={getTransaction} reviseTransaction={reviseTransaction} reverseTransaction={reverseTransaction} />,
    );
    await view.findByTestId('transaction-void-open');
    await user.press(view.getByTestId('transaction-void-open'));
    await view.findByTestId('transaction-void-reason');
    await user.press(view.getByTestId('transaction-void-submit'));
    await view.findByText('作废原因不能为空');
    expect(reverseTransaction).not.toHaveBeenCalled();

    await user.type(view.getByTestId('transaction-void-reason'), '重复记账');
    await user.press(view.getByTestId('transaction-void-submit'));
    await waitFor(() => expect(reverseTransaction).toHaveBeenCalledWith('tx-1', '"1"', expect.any(String), { reason: '重复记账' }));
    expect(view.getByText('作废成功：原交易已保留冲正记录。')).toBeTruthy();
  });

  it('版本冲突映射为可刷新提示', async () => {
    const getTransaction = jest.fn().mockResolvedValue({ data: transaction, meta: { requestId: 'req-1' } });
    const error = new Error('conflict') as Error & { problem?: { code: string } };
    error.problem = { code: 'VERSION_CONFLICT' };
    const reviseTransaction = jest.fn().mockRejectedValue(error);
    const reverseTransaction = jest.fn();
    const user = userEvent.setup();
    const view = await render(
      <TransactionDetailScreen transactionId="tx-1" getTransaction={getTransaction} reviseTransaction={reviseTransaction} reverseTransaction={reverseTransaction} />,
    );
    await view.findByTestId('transaction-revision-open');
    await user.press(view.getByTestId('transaction-revision-open'));
    await view.findByTestId('transaction-revision-reason');
    await user.type(view.getByTestId('transaction-revision-reason'), 'reason');
    await user.type(view.getByTestId('transaction-revision-account'), '11111111-1111-4111-8111-111111111111');
    await user.type(view.getByTestId('transaction-revision-amount'), '1.00');
    await user.type(view.getByTestId('transaction-revision-category'), '22222222-2222-4222-8222-222222222222');
    await user.press(view.getByTestId('transaction-revision-submit'));
    await view.findByText('交易已被其他设备修改，请刷新后重试。');
  });
});
