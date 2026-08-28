import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import type { Transaction, TransactionEnvelope } from '@/api/api-client';
import type { PostTransactionRequest } from '@/ledger/quick-record';

const TYPE_LABELS: Record<string, string> = {
  INCOME: '收入', EXPENSE: '支出', REFUND: '退款', TRANSFER: '转账',
  ADJUSTMENT: '余额调整', OPENING: '期初', REVERSAL: '冲正', REPAYMENT: '负债还款',
};
const STATUS_LABELS: Record<string, string> = {
  POSTED: '已入账', REVERSED: '已作废', SUPERSEDED: '已被修订', DRAFT: '草稿', DISCARDED: '已丢弃',
};
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface TransactionDetailScreenProps {
  transactionId: string | null;
  getTransaction: (transactionId: string) => Promise<TransactionEnvelope>;
  reviseTransaction: (
    transactionId: string,
    etag: string,
    idempotencyKey: string,
    body: { reason: string; replacement: PostTransactionRequest },
  ) => Promise<TransactionEnvelope>;
  reverseTransaction: (
    transactionId: string,
    etag: string,
    idempotencyKey: string,
    body: { reason: string },
  ) => Promise<TransactionEnvelope>;
}

function etag(transaction: Transaction): string {
  return `"${transaction.version}"`;
}

function problemMessage(error: unknown, fallback: string): string {
  const problem = (error as { problem?: { code?: string; detail?: string } }).problem;
  if (problem?.code === 'VERSION_CONFLICT') return '交易已被其他设备修改，请刷新后重试。';
  return problem?.detail ?? fallback;
}

/** 交易详情：修改与作废都提交语义命令，并由 ETag/幂等键保护重试和并发。 */
export function TransactionDetailScreen({
  transactionId,
  getTransaction,
  reviseTransaction,
  reverseTransaction,
}: TransactionDetailScreenProps) {
  const [envelope, setEnvelope] = useState<TransactionEnvelope | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [revision, setRevision] = useState(false);
  const [voiding, setVoiding] = useState(false);
  const [accountId, setAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [businessAt, setBusinessAt] = useState('');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const revisionKeyRef = useRef<string | null>(null);
  const voidKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!transactionId) {
      setMessage('缺少交易 ID。');
      return;
    }
    let cancelled = false;
    setEnvelope(null);
    getTransaction(transactionId)
      .then((result) => {
        if (!cancelled) setEnvelope(result);
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载交易：可能已下线或不可见。');
      });
    return () => {
      cancelled = true;
    };
  }, [getTransaction, transactionId]);

  function openRevision(transaction: Transaction) {
    setRevision(true);
    setAccountId('');
    setAmount('');
    setCategoryId('');
    setBusinessAt(transaction.businessAt);
    setNote('');
    setMessage(null);
  }

  async function submitRevision() {
    const transaction = envelope?.data;
    if (!transaction || !transactionId) return;
    if (!UUID_PATTERN.test(accountId.trim())) {
      setMessage('请填写有效的账户 ID');
      return;
    }
    if (!/^\d+(\.\d+)?$/.test(amount.trim())) {
      setMessage('金额必须是正数');
      return;
    }
    if (!UUID_PATTERN.test(categoryId.trim())) {
      setMessage('请填写有效的分类 ID');
      return;
    }
    if (!businessAt.trim()) {
      setMessage('业务时间不能为空');
      return;
    }
    if (!reason.trim()) {
      setMessage('修改原因不能为空');
      return;
    }
    const replacement = {
      type: transaction.type as 'INCOME' | 'EXPENSE',
      accountId: accountId.trim(),
      amount: amount.trim(),
      currency: transaction.entries[0]?.currency ?? 'CNY',
      categoryId: categoryId.trim(),
      businessAt: businessAt.trim(),
      timezone: transaction.timezone,
      note: note.trim() === '' ? null : note.trim(),
    } as PostTransactionRequest;

    revisionKeyRef.current ??= globalThis.crypto.randomUUID();
    setSubmitting(true);
    setMessage(null);
    try {
      const result = await reviseTransaction(transactionId, etag(transaction), revisionKeyRef.current, {
        reason: reason.trim(),
        replacement,
      });
      setEnvelope(result);
      setRevision(false);
      setReason('');
      revisionKeyRef.current = null;
      setMessage('修改成功：已生成替代交易。');
    } catch (error) {
      setMessage(problemMessage(error, '修改失败：网络或服务暂不可用。'));
    } finally {
      setSubmitting(false);
    }
  }

  async function submitVoid() {
    const transaction = envelope?.data;
    if (!transaction || !transactionId) return;
    if (!reason.trim()) {
      setMessage('作废原因不能为空');
      return;
    }
    voidKeyRef.current ??= globalThis.crypto.randomUUID();
    setSubmitting(true);
    setMessage(null);
    try {
      const result = await reverseTransaction(transactionId, etag(transaction), voidKeyRef.current, {
        reason: reason.trim(),
      });
      setEnvelope(result);
      setVoiding(false);
      setReason('');
      voidKeyRef.current = null;
      setMessage('作废成功：原交易已保留冲正记录。');
    } catch (error) {
      setMessage(problemMessage(error, '作废失败：网络或服务暂不可用。'));
    } finally {
      setSubmitting(false);
    }
  }

  const transaction = envelope?.data;

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }} testID="transaction-detail-screen">
      {transaction ? (
        <>
          <View className="gap-3 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" testID="transaction-detail">
            <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">
              {TYPE_LABELS[transaction.type] ?? transaction.type}
            </Text>
            <Text className="text-base text-ink-light dark:text-ink-dark">
              状态：{STATUS_LABELS[transaction.status] ?? transaction.status}
            </Text>
            <Text className="text-base text-ink-light dark:text-ink-dark">
              业务日期：{transaction.businessDate}（{transaction.timezone}）
            </Text>
            <View className="gap-1 border-t border-canvas-light pt-3 dark:border-canvas-dark">
              {transaction.entries.map((entry) => (
                <Text key={entry.id} className="text-sm text-muted-light dark:text-muted-dark">
                  {entry.sequenceNo}. {entry.direction === 'D' ? '借' : '贷'} {entry.amount} {entry.currency}
                </Text>
              ))}
            </View>
            <Text className="text-xs text-muted-light dark:text-muted-dark">交易 ID：{transaction.id}</Text>
          </View>

          {transaction.status === 'POSTED' && (transaction.type === 'INCOME' || transaction.type === 'EXPENSE') ? (
            <>
              {!revision && !voiding ? (
                <View className="flex-row gap-2">
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="修改交易"
                    onPress={() => openRevision(transaction)}
                    testID="transaction-revision-open"
                    className="min-h-11 flex-1 items-center justify-center rounded-lg border border-accent active:opacity-70"
                  >
                    <Text className="font-semibold text-ink-light dark:text-ink-dark">修改</Text>
                  </Pressable>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="作废交易"
                    onPress={() => { setVoiding(true); setMessage(null); }}
                    testID="transaction-void-open"
                    className="min-h-11 flex-1 items-center justify-center rounded-lg border border-destructive active:opacity-70"
                  >
                    <Text className="font-semibold text-destructive">作废</Text>
                  </Pressable>
                </View>
              ) : null}

              {revision ? (
                <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="transaction-revision-form">
                  <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">修改已确认交易</Text>
                  <TextInput value={reason} onChangeText={setReason} placeholder="修改原因" testID="transaction-revision-reason"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <TextInput value={accountId} onChangeText={setAccountId} placeholder="账户 ID" testID="transaction-revision-account"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <TextInput value={amount} onChangeText={setAmount} placeholder="金额" keyboardType="decimal-pad" testID="transaction-revision-amount"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <TextInput value={categoryId} onChangeText={setCategoryId} placeholder="分类 ID" testID="transaction-revision-category"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <TextInput value={businessAt} onChangeText={setBusinessAt} placeholder="业务时间 ISO-8601" testID="transaction-revision-business-at"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <TextInput value={note} onChangeText={setNote} placeholder="备注（可选）" testID="transaction-revision-note"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="提交修改"
                    accessibilityState={{ disabled: submitting }}
                    disabled={submitting}
                    onPress={() => void submitRevision()}
                    testID="transaction-revision-submit"
                    className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
                  >
                    <Text className="font-bold text-canvas-dark">{submitting ? '正在提交…' : '提交修改'}</Text>
                  </Pressable>
                </View>
              ) : null}

              {voiding ? (
                <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="transaction-void-form">
                  <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">作废已确认交易</Text>
                  <Text className="text-xs text-muted-light dark:text-muted-dark">
                    原交易不会删除；确认后服务端生成冲正交易。
                  </Text>
                  <TextInput value={reason} onChangeText={setReason} placeholder="作废原因" testID="transaction-void-reason"
                    className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="确认作废"
                    accessibilityState={{ disabled: submitting }}
                    disabled={submitting}
                    onPress={() => void submitVoid()}
                    testID="transaction-void-submit"
                    className={`min-h-11 items-center justify-center rounded-lg bg-destructive ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
                  >
                    <Text className="font-bold text-canvas-dark">{submitting ? '正在作废…' : '确认作废'}</Text>
                  </Pressable>
                </View>
              ) : null}
            </>
          ) : null}
        </>
      ) : (
        <Text className="text-base text-muted-light dark:text-muted-dark" accessibilityLiveRegion="polite">
          {message ?? '正在加载交易…'}
        </Text>
      )}

      {transaction && message ? (
        <Text accessibilityRole="alert" testID="transaction-detail-message" className="text-sm text-destructive">{message}</Text>
      ) : null}
    </ScrollView>
  );
}
