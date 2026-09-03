import { useRef, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';

import type { Category, PostTransactionRequest } from '@/api/api-client';
import { CategorySelect } from '@/categories/category-select';

interface LiabilityRepaymentFormProps {
  liabilityAccountId: string;
  currency: string;
  categories: Category[];
  timezone: string;
  keyFor: (signature: string) => string;
  createTransaction: (idempotencyKey: string, body: PostTransactionRequest) => Promise<{ data: { id: string } }>;
  onSuccess: (transactionId: string) => void;
}

interface RepaymentFieldErrors {
  fields: Partial<Record<'cashAccountId' | 'principalAmount' | 'interestAmount' | 'feeAmount' | 'interestCategoryId' | 'feeCategoryId', string>>;
}

function parseAmount(value: string): number | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : Number(trimmed);
}

function amountIsValid(value: string, decimals: number, allowZero: boolean): boolean {
  if (value.trim() === '') return allowZero;
  const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
  if (!pattern.test(value.trim())) return false;
  const amount = Number(value.trim());
  return Number.isFinite(amount) && (allowZero ? amount >= 0 : amount > 0);
}

function formatAmount(value: string, decimals: number): string {
  if (value.trim() === '') return decimals === 0 ? '0' : '0'.padEnd(decimals + 2, '0');
  const [integer, fraction = ''] = value.trim().split('.');
  return decimals === 0 ? integer : `${integer}.${fraction.padEnd(decimals, '0').slice(0, decimals)}`;
}

/** 语义还款：本金不计支出；利息和手续费 >0 时必须提供对应费用分类，由服务端入账。 */
export function LiabilityRepaymentForm({
  liabilityAccountId, currency, timezone, categories, keyFor, createTransaction, onSuccess,
}: LiabilityRepaymentFormProps) {
  const [cashAccountId, setCashAccountId] = useState('');
  const [principalAmount, setPrincipalAmount] = useState('');
  const [interestAmount, setInterestAmount] = useState('');
  const [feeAmount, setFeeAmount] = useState('');
  const [interestCategoryId, setInterestCategoryId] = useState('');
  const [feeCategoryId, setFeeCategoryId] = useState('');
  const [note, setNote] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const signatureKey = useRef(new Map<string, { businessAt: string; idempotencyKey: string }>());

  const decimals = currency === 'JPY' ? 0 : 2;

  function validate(): RepaymentFieldErrors {
    const errors: RepaymentFieldErrors = { fields: {} };
    if (cashAccountId.trim() === '') errors.fields.cashAccountId = '现金账户 ID 不能为空';
    if (!amountIsValid(principalAmount, decimals, false)) errors.fields.principalAmount = '本金必须是正数且符合币种精度';
    if (!amountIsValid(interestAmount, decimals, true)) errors.fields.interestAmount = '利息必须是非负数且符合币种精度';
    if (!amountIsValid(feeAmount, decimals, true)) errors.fields.feeAmount = '手续费必须是非负数且符合币种精度';
    if ((parseAmount(interestAmount) ?? 0) > 0 && interestCategoryId.trim() === '') {
      errors.fields.interestCategoryId = '利息 > 0 时必须提供利息分类 ID';
    }
    if ((parseAmount(feeAmount) ?? 0) > 0 && feeCategoryId.trim() === '') {
      errors.fields.feeCategoryId = '手续费 > 0 时必须提供手续费分类 ID';
    }
    return errors;
  }

  async function submit() {
    const errors = validate();
    const firstError = Object.values(errors.fields)[0];
    if (firstError) {
      setMessage(firstError);
      return;
    }
    const fields = {
      type: 'LIABILITY_REPAYMENT' as const,
      cashAccountId: cashAccountId.trim(),
      liabilityAccountId,
      currency,
      principalAmount: formatAmount(principalAmount, decimals),
      interestAmount: formatAmount(interestAmount, decimals),
      feeAmount: formatAmount(feeAmount, decimals),
      interestCategoryId: (parseAmount(interestAmount) ?? 0) > 0 ? interestCategoryId.trim() : null,
      feeCategoryId: (parseAmount(feeAmount) ?? 0) > 0 ? feeCategoryId.trim() : null,
      timezone,
      note: note.trim() === '' ? null : note.trim(),
    };
    // 业务时间只在首次提交固化：失败原样重试必须重放同一命令，不能生成第二笔账务事实。
    const signature = JSON.stringify(fields);
    let context = signatureKey.current.get(signature);
    if (!context) {
      context = { businessAt: new Date().toISOString(), idempotencyKey: keyFor(signature) };
      signatureKey.current.set(signature, context);
    }
    const payload: PostTransactionRequest = { ...fields, businessAt: context.businessAt };
    setSubmitting(true);
    setMessage(null);
    try {
      const envelope = await createTransaction(context.idempotencyKey, payload);
      signatureKey.current.delete(signature);
      onSuccess(envelope.data.id);
    } catch {
      // 同载荷可原样重试；失败不伪造成功。
      setMessage('保存失败：网络或服务暂不可用，可原样重试。');
    } finally {
      setSubmitting(false);
    }
  }

  const fields: Array<{ key: keyof RepaymentFieldErrors['fields']; label: string; placeholder: string; value: string; setter: (value: string) => void; testId: string }> = [
    { key: 'cashAccountId', label: '现金账户 ID（同币种资产账户）', placeholder: '现金账户 UUID', value: cashAccountId, setter: setCashAccountId, testId: 'repayment-cash-account' },
    { key: 'principalAmount', label: `本金（${currency}）`, placeholder: '0.00', value: principalAmount, setter: setPrincipalAmount, testId: 'repayment-principal' },
    { key: 'interestAmount', label: `利息（${currency}，0 表示无）`, placeholder: '0.00', value: interestAmount, setter: setInterestAmount, testId: 'repayment-interest' },
    { key: 'feeAmount', label: `手续费（${currency}，0 表示无）`, placeholder: '0.00', value: feeAmount, setter: setFeeAmount, testId: 'repayment-fee' },
  ];

  return (
    <View className="gap-4 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" testID="liability-repayment-form">
      <Text className="text-lg font-bold text-ink-light dark:text-ink-dark">还款</Text>
      <View className="gap-3">
        {fields.map((field) => (
          <View key={field.key} className="gap-1">
            <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{field.label}</Text>
            <TextInput
              value={field.value}
              onChangeText={field.setter}
              placeholder={field.placeholder}
              testID={field.testId}
              className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
            />
          </View>
        ))}
        <CategorySelect
          categories={categories}
          categoryType="EXPENSE"
          value={interestCategoryId}
          onChange={setInterestCategoryId}
          testID="repayment-interest-category"
          label="利息分类（利息 >0 时必填）"
        />
        <CategorySelect
          categories={categories}
          categoryType="EXPENSE"
          value={feeCategoryId}
          onChange={setFeeCategoryId}
          testID="repayment-fee-category"
          label="手续费分类（手续费 >0 时必填）"
        />
        <View className="gap-1">
          <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">备注（可选）</Text>
          <TextInput
            value={note}
            onChangeText={setNote}
            placeholder="可选"
            testID="repayment-note"
            className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
          />
        </View>
      </View>
      {message ? (
        <Text accessibilityRole="alert" testID="repayment-message" className="text-sm text-destructive">
          {message}
        </Text>
      ) : null}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="提交还款"
        accessibilityState={{ disabled: submitting }}
        disabled={submitting}
        onPress={() => void submit()}
        testID="repayment-submit"
        className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
      >
        <Text className="font-bold text-canvas-dark">{submitting ? '正在提交…' : '提交还款'}</Text>
      </Pressable>
      <Text className="text-xs text-muted-light dark:text-muted-dark">
        本金不计支出；利息与手续费分别入账。时区：{timezone}
      </Text>
    </View>
  );
}
