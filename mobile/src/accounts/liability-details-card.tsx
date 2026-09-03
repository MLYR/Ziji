import { useEffect, useRef, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';

import type { Account, LiabilityDetail, PutLiabilityDetailRequest } from '@/api/api-client';

interface LiabilityDetailsFormState {
  interestRate: string;
  loanDate: string;
  dueDate: string;
  billingDay: string;
  repaymentDay: string;
  currentAmountDue: string;
}

interface LiabilityDetailsCardProps {
  accountId: string;
  accountType: Account['accountType'];
  currency: string;
  getDetails: (accountId: string) => Promise<{ data: LiabilityDetail }>;
  putDetails: (
    accountId: string,
    precondition: { ifNoneMatch?: boolean; ifMatch?: string },
    idempotencyKey: string,
    body: PutLiabilityDetailRequest,
  ) => Promise<{ data: LiabilityDetail }>;
  keyFor: (signature: string) => string;
}

const EMPTY_FORM: LiabilityDetailsFormState = {
  interestRate: '',
  loanDate: '',
  dueDate: '',
  billingDay: '',
  repaymentDay: '',
  currentAmountDue: '',
};

function detailEtag(version: number): string {
  return `"${version}"`;
}

function isApplicable(accountType: Account['accountType'], field: keyof LiabilityDetailsFormState): boolean {
  if (accountType === 'CREDIT_CARD') return field !== 'loanDate' && field !== 'dueDate';
  if (accountType === 'LOAN' || accountType === 'CONSUMER_LOAN') return field !== 'billingDay';
  return true;
}

function formFromDetail(detail: LiabilityDetail): LiabilityDetailsFormState {
  return {
    interestRate: detail.interestRate ?? '',
    loanDate: detail.loanDate ?? '',
    dueDate: detail.dueDate ?? '',
    billingDay: detail.billingDay?.toString() ?? '',
    repaymentDay: detail.repaymentDay?.toString() ?? '',
    currentAmountDue: detail.currentAmountDue ?? '',
  };
}

function payloadFromForm(
  form: LiabilityDetailsFormState,
  accountType: Account['accountType'],
): PutLiabilityDetailRequest {
  const value = (field: keyof LiabilityDetailsFormState): string | number | null => {
    const raw = form[field].trim();
    if (!isApplicable(accountType, field) || raw === '') return null;
    if (field === 'billingDay' || field === 'repaymentDay') return Number(raw);
    return raw;
  };
  return {
    interestRate: value('interestRate') as string | null,
    loanDate: value('loanDate') as string | null,
    dueDate: value('dueDate') as string | null,
    billingDay: value('billingDay') as number | null,
    repaymentDay: value('repaymentDay') as number | null,
    currentAmountDue: value('currentAmountDue') as string | null,
  };
}

/** 负债详情只保存提醒元数据；账面负债始终来自 LedgerEntry，表单不能覆盖账务事实。 */
export function LiabilityDetailsCard({
  accountId, accountType, currency, getDetails, putDetails, keyFor,
}: LiabilityDetailsCardProps) {
  const [detail, setDetail] = useState<LiabilityDetail | null>(null);
  const [form, setForm] = useState<LiabilityDetailsFormState>(EMPTY_FORM);
  const [editing, setEditing] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const signatureKey = useRef(new Map<string, string>());

  useEffect(() => {
    let cancelled = false;
    getDetails(accountId)
      .then((envelope) => {
        if (cancelled) return;
        setDetail(envelope.data);
        setForm(formFromDetail(envelope.data));
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载负债详情：账户可能不存在或不可见。');
      });
    return () => { cancelled = true; };
  }, [accountId, getDetails]);

  async function save() {
    const payloadSignature = JSON.stringify(payloadFromForm(form, accountType));
    let idempotencyKey = signatureKey.current.get(payloadSignature);
    if (!idempotencyKey) {
      idempotencyKey = keyFor(payloadSignature);
      signatureKey.current.set(payloadSignature, idempotencyKey);
    }
    setSubmitting(true);
    setMessage(null);
    try {
      const version = detail?.version ?? 0;
      const envelope = await putDetails(
        accountId,
        version === 0 ? { ifNoneMatch: true } : { ifMatch: detailEtag(version) },
        idempotencyKey,
        payloadFromForm(form, accountType),
      );
      setDetail(envelope.data);
      setForm(formFromDetail(envelope.data));
      setEditing(false);
      signatureKey.current.delete(payloadSignature);
      setMessage('已保存。');
    } catch {
      // 版本冲突或网络失败统一提示可重试，不伪造保存成功。
      setMessage('保存失败：请检查输入或刷新后重试（版本冲突需重新加载）。');
    } finally {
      setSubmitting(false);
    }
  }

  const rows: Array<{ field: keyof LiabilityDetailsFormState; label: string; placeholder: string }> = [
    { field: 'interestRate', label: '年化利率（0.045 = 4.5%）', placeholder: '0.045' },
    { field: 'loanDate', label: '借款日期', placeholder: '2026-01-01' },
    { field: 'dueDate', label: '到期日期', placeholder: '2026-12-31' },
    { field: 'billingDay', label: '账单日（1-31）', placeholder: '28' },
    { field: 'repaymentDay', label: '还款日（1-31）', placeholder: '10' },
    { field: 'currentAmountDue', label: `当前应还金额（${currency}）`, placeholder: '0.00' },
  ];

  return (
    <View className="gap-4 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" testID="liability-details-card">
      <Text className="text-lg font-bold text-ink-light dark:text-ink-dark">负债详情</Text>
      {!editing ? (
        <>
          <View className="gap-2" testID="liability-details-view">
            {rows.filter((row) => isApplicable(accountType, row.field)).map((row) => (
              <View key={row.field} className="flex-row justify-between">
                <Text className="text-muted-light dark:text-muted-dark">{row.label}</Text>
                <Text className="font-medium text-ink-light dark:text-ink-dark">
                  {form[row.field].trim() === '' ? '—' : form[row.field]}
                </Text>
              </View>
            ))}
            <Text className="text-xs text-muted-light dark:text-muted-dark">
              注：详情仅为提醒元数据，账面负债来自账务事实。
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            onPress={() => { setEditing(true); setMessage(null); }}
            testID="liability-details-edit"
            className="min-h-11 items-center justify-center rounded-lg border border-accent"
          >
            <Text className="font-semibold text-accent">编辑详情</Text>
          </Pressable>
        </>
      ) : (
        <>
          <View className="gap-3">
            {rows.filter((row) => isApplicable(accountType, row.field)).map((row) => (
              <View key={row.field} className="gap-1">
                <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{row.label}</Text>
                <TextInput
                  value={form[row.field]}
                  onChangeText={(value) => setForm((previous) => ({ ...previous, [row.field]: value }))}
                  placeholder={row.placeholder}
                  testID={`liability-details-field-${row.field}`}
                  className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
                />
              </View>
            ))}
          </View>
          <View className="flex-row gap-2">
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: submitting }}
              disabled={submitting}
              onPress={() => void save()}
              testID="liability-details-save"
              className={`min-h-11 flex-1 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
            >
              <Text className="font-bold text-canvas-dark">{submitting ? '正在保存…' : '保存'}</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              onPress={() => { setEditing(false); setForm(formFromDetail(detail ?? { accountId, interestRate: null, loanDate: null, dueDate: null, billingDay: null, repaymentDay: null, currentAmountDue: null, version: 0 })); }}
              testID="liability-details-cancel"
              className="min-h-11 flex-1 items-center justify-center rounded-lg border border-accent/40"
            >
              <Text className="font-semibold text-ink-light dark:text-ink-dark">取消</Text>
            </Pressable>
          </View>
        </>
      )}
      {message ? (
        <Text accessibilityRole="alert" testID="liability-details-message" className="text-sm text-destructive">
          {message}
        </Text>
      ) : null}
    </View>
  );
}
