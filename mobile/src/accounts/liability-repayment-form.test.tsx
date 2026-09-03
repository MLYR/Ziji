import { render, userEvent, waitFor } from '@testing-library/react-native';

import { LiabilityRepaymentForm } from '@/accounts/liability-repayment-form';
import type { Category } from '@/api/api-client';
import type { PostTransactionRequest } from '@/api/api-client';

const liabilityAccountId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a1';
const cashAccountId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a2';
const interestCategoryId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a3';
const feeCategoryId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a4';

const categories: Category[] = [
  { id: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa', categoryType: 'EXPENSE', name: '利息支出', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
];

async function renderForm(createTransaction: jest.Mock, user: ReturnType<typeof userEvent.setup>) {
  const keyFor = jest.fn((signature: string) => `key:${signature}`);
  const view = await render(
    <LiabilityRepaymentForm
      liabilityAccountId={liabilityAccountId}
      currency="CNY"
      timezone="Asia/Shanghai"
      categories={categories}
      keyFor={keyFor}
      createTransaction={createTransaction}
      onSuccess={jest.fn()}
    />,
  );
  return { keyFor, view };
}

describe('Mobile 语义还款表单', () => {
  it('利息与手续费大于零时必须提供对应分类', async () => {
    const user = userEvent.setup();
    const createTransaction = jest.fn();
    const { view } = await renderForm(createTransaction, user);
    await user.type(view.getByTestId('repayment-cash-account'), cashAccountId);
    await user.type(view.getByTestId('repayment-principal'), '1000.00');
    await user.type(view.getByTestId('repayment-interest'), '50.00');
    await user.press(view.getByTestId('repayment-submit'));
    await waitFor(() => expect(view.getByTestId('repayment-message')).toBeTruthy());
    expect(createTransaction).not.toHaveBeenCalled();
  });

  it('提交本金+利息+手续费并格式化金额，同载荷失败重试复用同一幂等键', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-08-29T01:00:00.000Z'));
    const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });
    const createTransaction = jest.fn()
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ data: { id: 'tx-1' } });
    const { keyFor, view } = await renderForm(createTransaction, user);

    await user.type(view.getByTestId('repayment-cash-account'), cashAccountId);
    await user.type(view.getByTestId('repayment-principal'), '1000');
    await user.type(view.getByTestId('repayment-interest'), '50.5');
    await user.type(view.getByTestId('repayment-fee'), '10');
    await user.press(view.getByTestId('repayment-interest-category'));
    await user.press(view.getByTestId('repayment-interest-category-option-0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa'));
    await user.press(view.getByTestId('repayment-fee-category'));
    await user.press(view.getByTestId('repayment-fee-category-option-0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa'));
    await user.press(view.getByTestId('repayment-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(1));

    const payload = createTransaction.mock.calls[0][1] as PostTransactionRequest;
    expect(payload).toMatchObject({
      type: 'LIABILITY_REPAYMENT',
      cashAccountId,
      liabilityAccountId,
      currency: 'CNY',
      principalAmount: '1000.00',
      interestAmount: '50.50',
      feeAmount: '10.00',
      interestCategoryId: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa',
      feeCategoryId: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa',
      timezone: 'Asia/Shanghai',
    });

    await user.press(view.getByTestId('repayment-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(2));
    expect(createTransaction.mock.calls[1][0]).toBe(createTransaction.mock.calls[0][0]);
    expect(keyFor).toHaveBeenCalledTimes(1);
    jest.useRealTimers();
  });

  it('本金为零或负数被校验拒绝', async () => {
    const user = userEvent.setup();
    const createTransaction = jest.fn();
    const { view } = await renderForm(createTransaction, user);
    await user.type(view.getByTestId('repayment-cash-account'), cashAccountId);
    await user.type(view.getByTestId('repayment-principal'), '0');
    await user.press(view.getByTestId('repayment-submit'));
    await waitFor(() => expect(view.getByTestId('repayment-message')).toBeTruthy());
    expect(createTransaction).not.toHaveBeenCalled();
  });
});
