import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileTransactionApiClient } from '@/auth/default-auth-session';
import type { TransactionEnvelope } from '@/api/api-client';

const TYPE_LABELS: Record<string, string> = {
  INCOME: '收入',
  EXPENSE: '支出',
  REFUND: '退款',
  TRANSFER: '转账',
  ADJUSTMENT: '余额调整',
  OPENING: '期初',
  REVERSAL: '冲正',
  REPAYMENT: '负债还款',
};

const STATUS_LABELS: Record<string, string> = {
  POSTED: '已入账',
  REVERSED: '已作废',
  SUPERSEDED: '已被修订',
  DRAFT: '草稿',
  DISCARDED: '已丢弃',
};

/** 交易详情路由：以路径参数 id 调用类型化 getTransaction，不解析任何内部账务结构。 */
export default function TransactionDetailRoute() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const transactionId = typeof params.id === 'string' ? params.id : null;
  const [envelope, setEnvelope] = useState<TransactionEnvelope | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!transactionId) {
      setMessage('缺少交易 ID。');
      return;
    }
    let cancelled = false;
    mobileTransactionApiClient.getTransaction(transactionId)
      .then((result) => {
        if (!cancelled) setEnvelope(result);
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载交易：可能已下线或不可见。');
      });
    return () => {
      cancelled = true;
    };
  }, [transactionId]);

  const transaction = envelope?.data;

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="返回"
          onPress={() => router.back()}
          testID="transaction-detail-back"
        >
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        {transaction ? (
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
        ) : (
          <Text className="text-base text-muted-light dark:text-muted-dark" accessibilityLiveRegion="polite">
            {message ?? '正在加载交易…'}
          </Text>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
