import { buildQuickEntryPayload, payloadSignature, validateQuickEntry } from '@/ledger/quick-record';
import { submitQuickEntry } from '@/ledger/quick-record-submit';

const baseFields = {
  accountId: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a1',
  amount: '12.50',
  categoryId: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a2',
  currency: 'CNY' as const,
  timezone: 'Asia/Shanghai',
  businessAt: '2026-08-28T02:00:00.000Z',
  note: ' 午餐 ',
};

describe('快速记账校验与载荷', () => {
  it('合法字段通过校验并构造语义命令，客户端不提交分录或内部科目', () => {
    const { errors } = validateQuickEntry(baseFields);
    expect(Object.keys(errors)).toHaveLength(0);

    const payload = buildQuickEntryPayload('EXPENSE', baseFields);
    expect(payload).toEqual({
      type: 'EXPENSE',
      accountId: baseFields.accountId,
      amount: '12.50',
      currency: 'CNY',
      categoryId: baseFields.categoryId,
      businessAt: baseFields.businessAt,
      note: '午餐',
      timezone: 'Asia/Shanghai',
    });
  });

  it('金额精度按币种校验：JPY 不允许小数位，CNY 最多两位', () => {
    const jpy = { ...baseFields, currency: 'JPY' as const, amount: '12.5' };
    expect(validateQuickEntry(jpy).errors.amount).toBe('该币种金额不支持小数位');

    const cny = { ...baseFields, amount: '12.345' };
    expect(validateQuickEntry(cny).errors.amount).toBe('金额最多 2 位小数');
    expect(validateQuickEntry({ ...cny, amount: '12.34' }).errors.amount).toBeUndefined();
  });

  it('非法 UUID 与空金额被拒绝', () => {
    const result = validateQuickEntry({ ...baseFields, accountId: 'not-a-uuid', amount: '', categoryId: 'x' });
    expect(result.errors.accountId).toBe('请填写有效的账户 ID');
    expect(result.errors.amount).toBe('金额不能为空');
    expect(result.errors.categoryId).toBe('请填写有效的分类 ID');
  });
});

describe('快速记账提交', () => {
  const fields = { ...baseFields };

  it('同载荷重试复用同一幂等键，载荷变化换新键', async () => {
    const createTransaction = jest.fn()
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ data: { id: 'tx-1' } });
    const signatures: string[] = [];
    const keyFor = (signature: string) => {
      signatures.push(signature);
      // 屏幕层按签名缓存幂等键：同签名复用已有键，这里以递增键模拟。
      return `key-${signatures.length}`;
    };

    const first = await submitQuickEntry('EXPENSE', fields, { keyFor, createTransaction });
    const second = await submitQuickEntry('INCOME', fields, { keyFor, createTransaction });

    expect(first.state).toBe('FAILED');
    expect(second.state).toBe('SUCCEEDED');
    expect(second.transactionId).toBe('tx-1');
    // 同载荷重试产生相同签名，屏幕据此复用同一幂等键；类型变化则产生不同签名（换键）。
    expect(signatures[1]).not.toBe(signatures[0]);
    expect(createTransaction.mock.calls[0][0]).toBe('key-1');
    expect(createTransaction.mock.calls[1][0]).toBe('key-2');

    // 同载荷再次提交得到同一签名：屏幕将复用同一幂等键重试。
    const retrySignature = payloadSignature(buildQuickEntryPayload('EXPENSE', fields));
    expect(signatures[0]).toBe(retrySignature);
  });

  it('服务端幂等冲突映射为可读提示，不静默重试', async () => {
    const createTransaction = jest.fn().mockRejectedValue({
      problem: { code: 'IDEMPOTENCY_KEY_REUSED', status: 409, title: 'Conflict' },
    });
    const result = await submitQuickEntry('EXPENSE', fields, {
      keyFor: () => 'key-fixed',
      createTransaction,
    });
    expect(result.state).toBe('FAILED');
    expect(result.message).toBe('幂等键冲突，请修改内容后重试。');
  });

  it('校验失败不发起网络请求', async () => {
    const createTransaction = jest.fn();
    const result = await submitQuickEntry('EXPENSE', { ...fields, amount: 'abc' }, {
      keyFor: () => 'key-x',
      createTransaction,
    });
    expect(result.state).toBe('FAILED');
    expect(createTransaction).not.toHaveBeenCalled();
  });
});
