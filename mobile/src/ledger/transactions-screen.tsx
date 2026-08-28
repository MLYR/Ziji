import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import type { Transaction, TransactionListFilters } from '@/api/api-client';

const TYPE_OPTIONS = ['INCOME', 'EXPENSE', 'REFUND', 'TRANSFER', 'ADJUSTMENT', 'REPAYMENT'] as const;
const TYPE_LABELS: Record<string, string> = {
  INCOME: '收入', EXPENSE: '支出', REFUND: '退款', TRANSFER: '转账',
  ADJUSTMENT: '余额调整', REVERSAL: '冲正', REPAYMENT: '负债还款', OPENING: '期初',
};
const STATUS_LABELS: Record<string, string> = {
  POSTED: '已入账', REVERSED: '已作废', SUPERSEDED: '已被修订', DRAFT: '草稿', DISCARDED: '已丢弃',
};

export interface TransactionsScreenProps {
  listTransactions: (limit: number, filters?: TransactionListFilters) => Promise<{
    data: Transaction[];
    meta: { nextCursor: string | null; hasMore: boolean };
  }>;
  onViewTransaction: (transactionId: string) => void;
  onOpenQuickRecord: () => void;
}

type ActiveFilters = Pick<TransactionListFilters, 'accountId' | 'type' | 'dateFrom' | 'dateTo' | 'categoryId'>;

/** 流水列表：条件改变后重新查询；服务端负责筛选、排序和权限，Mobile 只渲染事实。 */
export function TransactionsScreen({ listTransactions, onViewTransaction, onOpenQuickRecord }: TransactionsScreenProps) {
  const [accountId, setAccountId] = useState('');
  const [type, setType] = useState<ActiveFilters['type']>(undefined);
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [filters, setFilters] = useState<ActiveFilters>({});
  const [transactions, setTransactions] = useState<Transaction[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setTransactions(null);
    setMessage(null);
    listTransactions(50, filters)
      .then((envelope) => {
        if (cancelled) return;
        setTransactions(envelope.data);
        setCursor(envelope.meta.nextCursor);
        setHasMore(envelope.meta.hasMore);
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载流水：网络或服务暂不可用。');
      });
    return () => {
      cancelled = true;
    };
  }, [filters, listTransactions]);

  const applyFilters = useCallback(() => {
    setFilters({
      accountId: accountId.trim() || undefined,
      type,
      dateFrom: dateFrom.trim() || undefined,
      dateTo: dateTo.trim() || undefined,
      categoryId: categoryId.trim() || undefined,
    });
  }, [accountId, categoryId, dateFrom, dateTo, type]);

  async function loadNext() {
    if (!cursor) return;
    setMessage(null);
    try {
      const envelope = await listTransactions(50, { ...filters, cursor });
      setTransactions((current) => [...(current ?? []), ...envelope.data]);
      setCursor(envelope.meta.nextCursor);
      setHasMore(envelope.meta.hasMore);
    } catch {
      setMessage('加载下一页失败，可重试。');
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }} testID="transactions-screen">
      <View className="flex-row items-center justify-between">
        <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">流水</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="打开快速记账"
          onPress={onOpenQuickRecord}
          testID="transactions-open-quick-record"
          className="min-h-11 items-center justify-center rounded-lg border border-accent px-4"
        >
          <Text className="font-semibold text-ink-light dark:text-ink-dark">记一笔</Text>
        </Pressable>
      </View>

      <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">筛选</Text>
        <TextInput
          value={accountId}
          onChangeText={setAccountId}
          placeholder="账户 ID（可选）"
          testID="transactions-filter-account"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
        <View className="flex-row flex-wrap gap-2">
          {TYPE_OPTIONS.map((option) => (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityState={{ selected: type === option }}
              onPress={() => setType(type === option ? undefined : option)}
              testID={`transactions-filter-type-${option}`}
              className={`min-h-9 items-center justify-center rounded-lg border px-3 ${type === option ? 'border-accent bg-accent' : 'border-accent/40'}`}
            >
              <Text className={type === option ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>
                {TYPE_LABELS[option]}
              </Text>
            </Pressable>
          ))}
        </View>
        <View className="flex-row gap-2">
          <TextInput
            value={dateFrom}
            onChangeText={setDateFrom}
            placeholder="开始日期 YYYY-MM-DD"
            testID="transactions-filter-date-from"
            className="min-h-11 flex-1 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
          />
          <TextInput
            value={dateTo}
            onChangeText={setDateTo}
            placeholder="结束日期 YYYY-MM-DD"
            testID="transactions-filter-date-to"
            className="min-h-11 flex-1 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
          />
        </View>
        <TextInput
          value={categoryId}
          onChangeText={setCategoryId}
          placeholder="分类 ID（可选）"
          testID="transactions-filter-category"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="应用筛选"
          onPress={applyFilters}
          testID="transactions-apply-filters"
          className="min-h-11 items-center justify-center rounded-lg bg-accent active:opacity-70"
        >
          <Text className="font-bold text-canvas-dark">应用筛选</Text>
        </Pressable>
      </View>

      {message ? (
        <Text accessibilityRole="alert" testID="transactions-message" className="text-sm text-destructive">{message}</Text>
      ) : null}

      {transactions === null ? (
        <Text className="text-sm text-muted-light dark:text-muted-dark">正在加载流水…</Text>
      ) : transactions.length === 0 ? (
        <Text testID="transactions-empty" className="text-sm text-muted-light dark:text-muted-dark">当前筛选没有流水。</Text>
      ) : (
        <View className="gap-2">
          {transactions.map((transaction) => (
            <Pressable
              key={transaction.id}
              accessibilityRole="button"
              accessibilityLabel={`打开交易 ${transaction.id}`}
              onPress={() => onViewTransaction(transaction.id)}
              testID={`transaction-row-${transaction.id}`}
              className="rounded-xl bg-surface-light p-4 dark:bg-surface-dark"
            >
              <View className="flex-row items-center justify-between">
                <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">
                  {TYPE_LABELS[transaction.type] ?? transaction.type}
                </Text>
                <Text className="text-xs text-muted-light dark:text-muted-dark">
                  {STATUS_LABELS[transaction.status] ?? transaction.status}
                </Text>
              </View>
              <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
                {transaction.businessDate} · 版本 {transaction.version}
              </Text>
            </Pressable>
          ))}
          {hasMore ? (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="加载更多流水"
              onPress={() => void loadNext()}
              testID="transactions-load-next"
              className="min-h-11 items-center justify-center rounded-lg border border-accent active:opacity-70"
            >
              <Text className="font-semibold text-ink-light dark:text-ink-dark">加载更多</Text>
            </Pressable>
          ) : null}
        </View>
      )}
    </ScrollView>
  );
}
