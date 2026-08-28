import type { components } from '@ziji/api-types';

export type PostTransactionRequest = components['schemas']['PostTransactionRequest'];
export type Currency = components['schemas']['Currency'];

export type QuickEntryType = 'EXPENSE' | 'INCOME';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface QuickEntryFields {
  accountId: string;
  amount: string;
  categoryId: string;
  currency: Currency;
  timezone: string;
  businessAt: string;
  note?: string;
}

export interface QuickEntryValidation {
  errors: Partial<Record<'accountId' | 'amount' | 'categoryId', string>>;
}

function amountMatchesPrecision(value: string, minorUnits: number): boolean {
  const pattern = minorUnits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${minorUnits}})?$`);
  return pattern.test(value);
}

export function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2;
}

/** 快速记账客户端校验：金额精度按账户币种、账户与分类为服务端 UUID。 */
export function validateQuickEntry(fields: QuickEntryFields): QuickEntryValidation {
  const errors: QuickEntryValidation['errors'] = {};
  if (!UUID_PATTERN.test(fields.accountId)) errors.accountId = '请填写有效的账户 ID';
  if (fields.amount.trim() === '') errors.amount = '金额不能为空';
  else if (!/^\d+(\.\d+)?$/.test(fields.amount.trim())) errors.amount = '金额必须是正数';
  else if (!amountMatchesPrecision(fields.amount.trim(), currencyMinorUnits(fields.currency))) {
    errors.amount = currencyMinorUnits(fields.currency) === 0
      ? '该币种金额不支持小数位'
      : `金额最多 ${currencyMinorUnits(fields.currency)} 位小数`;
  }
  if (!UUID_PATTERN.test(fields.categoryId)) errors.categoryId = '请填写有效的分类 ID';
  return { errors };
}

/** 构造公共语义命令；客户端不接触分录、内部科目或时区换算细节。 */
export function buildQuickEntryPayload(
  type: QuickEntryType,
  fields: QuickEntryFields,
): PostTransactionRequest {
  return {
    type,
    accountId: fields.accountId,
    amount: fields.amount.trim(),
    currency: fields.currency,
    categoryId: fields.categoryId,
    businessAt: fields.businessAt,
    note: fields.note?.trim() ? fields.note.trim() : null,
    timezone: fields.timezone,
  } as PostTransactionRequest;
}

/** 幂等键语义：同载荷重试复用同一键；载荷变化由调用方重新生成。 */
export function payloadSignature(payload: PostTransactionRequest): string {
  return JSON.stringify(payload);
}
