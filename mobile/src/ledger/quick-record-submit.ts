import {
  buildQuickEntryPayload,
  payloadSignature,
  validateQuickEntry,
  type QuickEntryFields,
  type QuickEntryType,
} from '@/ledger/quick-record';

export type CreateTransactionFn = (
  idempotencyKey: string,
  body: ReturnType<typeof buildQuickEntryPayload>,
) => Promise<{ data: { id: string } }>;

export type QuickEntrySubmitState = 'IDLE' | 'SUBMITTING' | 'SUCCEEDED' | 'FAILED';

export interface QuickEntrySubmitResult {
  state: 'SUCCEEDED' | 'FAILED';
  transactionId?: string;
  message?: string;
}

/**
 * 快速记账提交：同载荷重试复用同一幂等键（网络/5xx 安全重试），
 * 载荷变化后换新键，避免同键异参 IDEMPOTENCY_KEY_REUSED。
 */
export async function submitQuickEntry(
  type: QuickEntryType,
  fields: QuickEntryFields,
  options: { keyFor: (signature: string) => string; createTransaction: CreateTransactionFn },
): Promise<QuickEntrySubmitResult> {
  const validation = validateQuickEntry(fields);
  if (Object.keys(validation.errors).length > 0) {
    return { state: 'FAILED', message: Object.values(validation.errors)[0] };
  }
  const payload = buildQuickEntryPayload(type, fields);
  const key = options.keyFor(payloadSignature(payload));
  try {
    const envelope = await options.createTransaction(key, payload);
    return { state: 'SUCCEEDED', transactionId: envelope.data.id };
  } catch (error) {
    const problem = (error as { problem?: { detail?: string; title?: string; code?: string } }).problem;
    const message = problem
      ? problem.code === 'IDEMPOTENCY_KEY_REUSED'
        ? '幂等键冲突，请修改内容后重试。'
        : problem.detail ?? problem.title ?? '保存失败'
      : '网络或服务暂不可用，请稍后重试。';
    return { state: 'FAILED', message };
  }
}
