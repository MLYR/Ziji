import { render, userEvent, waitFor } from '@testing-library/react-native';

import { LiabilityDetailsCard } from '@/accounts/liability-details-card';
import type { LiabilityDetailEnvelope, PutLiabilityDetailRequest } from '@/api/api-client';

const accountId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a1';

function emptyDetail(): LiabilityDetailEnvelope['data'] {
  return {
    accountId,
    interestRate: null,
    loanDate: null,
    dueDate: null,
    billingDay: null,
    repaymentDay: null,
    currentAmountDue: null,
    version: 0,
  };
}

describe('Mobile 负债详情卡', () => {
  it('加载并展示与账户类型匹配的字段，信用卡不显示借款/到期日', async () => {
    const user = userEvent.setup();
    const getDetails = jest.fn().mockResolvedValue({ data: { ...emptyDetail(), interestRate: '0.045', repaymentDay: 10, version: 1 } });
    const view = await render(
      <LiabilityDetailsCard
        accountId={accountId}
        accountType="CREDIT_CARD"
        currency="CNY"
        getDetails={getDetails}
        putDetails={jest.fn()}
        keyFor={(s) => `key:${s}`}
      />,
    );
    await waitFor(() => expect(view.getByText('0.045')).toBeTruthy());
    expect(view.getByText('10')).toBeTruthy();
    expect(view.queryByText('借款日期')).toBeNull();
    expect(view.queryByText('到期日期')).toBeNull();
    expect(view.getByText('账单日（1-31）')).toBeTruthy();
  });

  it('首次保存使用 If-None-Match:* 并提交完整替换载荷', async () => {
    const user = userEvent.setup();
    const putDetails = jest.fn().mockResolvedValue({ data: { ...emptyDetail(), interestRate: '0.03', version: 1 } });
    const view = await render(
      <LiabilityDetailsCard
        accountId={accountId}
        accountType="LOAN"
        currency="CNY"
        getDetails={jest.fn().mockResolvedValue({ data: emptyDetail() })}
        putDetails={putDetails}
        keyFor={(s) => `key:${s}`}
      />,
    );
    await waitFor(() => expect(view.getByTestId('liability-details-edit')).toBeTruthy());
    await user.press(view.getByTestId('liability-details-edit'));
    await user.type(view.getByTestId('liability-details-field-interestRate'), '0.03');
    await user.press(view.getByTestId('liability-details-save'));

    await waitFor(() => expect(putDetails).toHaveBeenCalledTimes(1));
    expect(putDetails.mock.calls[0][1]).toEqual({ ifNoneMatch: true });
    const body: PutLiabilityDetailRequest = putDetails.mock.calls[0][3];
    expect(body.interestRate).toBe('0.03');
    expect(body.billingDay).toBeNull(); // LOAN 不适用账单日
    expect(body.loanDate).toBeNull();
  });

  it('保存失败提示可重试，不伪造成功', async () => {
    const user = userEvent.setup();
    const putDetails = jest.fn().mockRejectedValue(new Error('conflict'));
    const view = await render(
      <LiabilityDetailsCard
        accountId={accountId}
        accountType="OTHER"
        currency="CNY"
        getDetails={jest.fn().mockResolvedValue({ data: emptyDetail() })}
        putDetails={putDetails}
        keyFor={(s) => `key:${s}`}
      />,
    );
    await waitFor(() => expect(view.getByTestId('liability-details-edit')).toBeTruthy());
    await user.press(view.getByTestId('liability-details-edit'));
    await user.press(view.getByTestId('liability-details-save'));

    await waitFor(() => expect(view.getByTestId('liability-details-message').props.children).toContain('保存失败'));
  });
});
