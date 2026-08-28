import { useRef, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';

import {
  buildQuickEntryPayload,
  payloadSignature,
  validateQuickEntry,
  type PostTransactionRequest,
  type QuickEntryType,
} from '@/ledger/quick-record';

interface QuickRecordScreenProps {
  currency: 'CNY' | 'USD' | 'HKD' | 'JPY' | 'EUR';
  timezone: string;
  keyFor: (signature: string) => string;
  onSuccess: (transactionId: string) => void;
  createTransaction: (idempotencyKey: string, body: PostTransactionRequest) => Promise<{ data: { id: string } }>;
}

/**
 * 快速记账：只覆盖支出与收入两类高频资金动作。
 * 幂等键与载荷内容绑定：同载荷重试复用同一键，载荷变化立即换新键。
 * 账户与分类以服务端 UUID 提交；分类管理接口（BE-CAT-001）未开放前由用户粘贴分类 ID。
 */
export function QuickRecordScreen({ currency, timezone, keyFor, onSuccess, createTransaction }: QuickRecordScreenProps) {
  const [type, setType] = useState<QuickEntryType>('EXPENSE');
  const [accountId, setAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const keyBySignature = useRef(new Map<string, string>());

  function buildPayload() {
    const businessAt = new Date().toISOString();
    const fields = { accountId, amount, categoryId, currency, timezone, businessAt, note };
    const validation = validateQuickEntry(fields);
    const payload = Object.keys(validation.errors).length === 0 ? buildQuickEntryPayload(type, fields) : null;
    return { validation, payload };
  }

  async function submit() {
    const { validation, payload } = buildPayload();
    const firstError = Object.values(validation.errors)[0];
    if (!payload || firstError) {
      setMessage(firstError ?? '请检查输入。');
      return;
    }
    const signature = payloadSignature(payload);
    let key = keyBySignature.current.get(signature);
    if (!key) {
      key = keyFor(signature);
      keyBySignature.current.set(signature, key);
    }
    setSubmitting(true);
    setMessage(null);
    try {
      const envelope = await createTransaction(key, payload);
      keyBySignature.current.delete(signature);
      onSuccess(envelope.data.id);
    } catch {
      // 同载荷可安全重试；失败信息在 UI 呈现，不伪造成功。
      setMessage('保存失败：网络或服务暂不可用，可原样重试。');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <View className="gap-4 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" testID="quick-record-screen">
      <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">快速记账</Text>
      <View className="flex-row gap-2">
        {(['EXPENSE', 'INCOME'] as QuickEntryType[]).map((option) => (
          <Pressable
            key={option}
            accessibilityRole="button"
            accessibilityState={{ selected: type === option }}
            onPress={() => setType(option)}
            testID={`quick-record-type-${option}`}
            className={`flex-1 min-h-11 items-center justify-center rounded-lg border ${type === option ? 'border-accent bg-accent' : 'border-accent/40'}`}
          >
            <Text className={`font-semibold ${type === option ? 'text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}`}>
              {option === 'EXPENSE' ? '支出' : '收入'}
            </Text>
          </Pressable>
        ))}
      </View>

      <View className="gap-1">
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">账户 ID</Text>
        <TextInput
          value={accountId}
          onChangeText={setAccountId}
          placeholder="账户 ID"
          testID="quick-record-account"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
      </View>
      <View className="gap-1">
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">金额（{currency}）</Text>
        <TextInput
          value={amount}
          onChangeText={setAmount}
          placeholder="0.00"
          keyboardType="decimal-pad"
          testID="quick-record-amount"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
      </View>
      <View className="gap-1">
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">分类 ID</Text>
        <TextInput
          value={categoryId}
          onChangeText={setCategoryId}
          placeholder="分类 ID（分类管理接口开放后可直接选择）"
          testID="quick-record-category"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
      </View>
      <View className="gap-1">
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">备注（可选）</Text>
        <TextInput
          value={note}
          onChangeText={setNote}
          placeholder="可选"
          testID="quick-record-note"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
      </View>

      {message ? (
        <Text accessibilityRole="alert" testID="quick-record-message" className="text-sm text-destructive">
          {message}
        </Text>
      ) : null}

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="保存交易"
        accessibilityState={{ disabled: submitting }}
        disabled={submitting}
        onPress={() => void submit()}
        testID="quick-record-submit"
        className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
      >
        <Text className="font-bold text-canvas-dark">{submitting ? '正在保存…' : '保存交易'}</Text>
      </Pressable>
      <Text className="text-xs text-muted-light dark:text-muted-dark">
        提交携带幂等键：同内容重试不会产生重复交易。时区：{timezone}
      </Text>
    </View>
  );
}
