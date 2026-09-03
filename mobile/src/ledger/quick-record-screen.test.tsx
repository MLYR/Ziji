import { render, userEvent, waitFor } from '@testing-library/react-native';

import { QuickRecordScreen } from '@/ledger/quick-record-screen';
import type { Category } from '@/api/api-client';

const accountId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a1';
const categoryId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a2';

const categories: Category[] = [
  { id: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa', categoryType: 'EXPENSE', name: '餐饮', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
];

async function renderQuickRecord(createTransaction: jest.Mock, user: ReturnType<typeof userEvent.setup>) {
  const keyFor = jest.fn((signature: string) => `key:${signature}`);
  const view = await render(
    <QuickRecordScreen
      currency="CNY"
      timezone="Asia/Shanghai"
      categories={categories}
      keyFor={keyFor}
      onSuccess={jest.fn()}
      createTransaction={createTransaction}
    />,
  );
  await user.type(view.getByTestId('quick-record-account'), accountId);
  await user.type(view.getByTestId('quick-record-amount'), '12.50');
  await user.press(view.getByTestId('quick-record-category'));
  await user.press(view.getByTestId('quick-record-category-option-0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0aa'));
  await user.type(view.getByTestId('quick-record-note'), ' 午餐 ');
  return { keyFor, view };
}

describe('Mobile 快速记账重试', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it('失败后原样重试时复用同一 businessAt、完整载荷与幂等键', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-08-29T01:00:00.000Z'));
    const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });
    const createTransaction = jest.fn()
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ data: { id: 'tx-1' } });
    const { keyFor, view } = await renderQuickRecord(createTransaction, user);

    await user.press(view.getByTestId('quick-record-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(1));
    await view.findByTestId('quick-record-message');

    jest.setSystemTime(new Date('2026-08-29T01:05:00.000Z'));
    await user.press(view.getByTestId('quick-record-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(2));

    expect(createTransaction.mock.calls[1]).toEqual(createTransaction.mock.calls[0]);
    expect(createTransaction.mock.calls[0][1]).toMatchObject({
      note: '午餐',
    });
    expect(createTransaction.mock.calls[0][1].businessAt).toMatch(/^2026-08-29T01:00:00\./);
    expect(keyFor).toHaveBeenCalledTimes(1);
  });

  it('失败后修改业务输入会创建新的业务时间和幂等键', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-08-29T01:00:00.000Z'));
    const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });
    const createTransaction = jest.fn().mockRejectedValue(new Error('network down'));
    const { keyFor, view } = await renderQuickRecord(createTransaction, user);

    await user.press(view.getByTestId('quick-record-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(1));

    jest.setSystemTime(new Date('2026-08-29T01:05:00.000Z'));
    await user.clear(view.getByTestId('quick-record-amount'));
    await user.type(view.getByTestId('quick-record-amount'), '13.50');
    await user.press(view.getByTestId('quick-record-submit'));
    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(2));

    expect(createTransaction.mock.calls[1][0]).not.toBe(createTransaction.mock.calls[0][0]);
    expect(createTransaction.mock.calls[1][1]).toMatchObject({ amount: '13.50' });
    expect(createTransaction.mock.calls[1][1].businessAt).toMatch(/^2026-08-29T01:05:00\./);
    expect(keyFor).toHaveBeenCalledTimes(2);
  });
});
