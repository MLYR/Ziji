import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { mobileDashboardApiClient, mobileTransactionApiClient } from '@/auth/default-auth-session';
import type { components } from '@ziji/api-types';

const DAY_MS = 24 * 3600 * 1000;

type Dashboard = components['schemas']['Dashboard'];
type Transaction = components['schemas']['Transaction'];

const WARNING_LABELS: Record<string, string> = {
  MISSING_EXCHANGE_RATES: '部分账户缺少汇率，未计入折算总额',
  UNPRICED_INSTRUMENTS: '部分持仓缺少价格，未计入估值',
  STALE_MARKET_DATA: '行情数据过期',
};

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

interface HomeDashboardPanelProps {
  onOpenQuickRecord: () => void;
}

/**
 * 首页核心指标面板：五个核心指标、质量告警、趋势摘要与近期流水。
 * 全部数值来自服务端 Dashboard 与统计序列；加载/错误/空态显式区分，不伪造财务数字。
 */
export function HomeDashboardPanel({ onOpenQuickRecord }: HomeDashboardPanelProps) {
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [trend, setTrend] = useState<{ first: string; last: string } | null>(null);
  const [recent, setRecent] = useState<Transaction[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      setMessage(null);
      try {
        const today = new Date();
        const from = new Date(today.getTime() - 29 * DAY_MS).toISOString().slice(0, 10);
        const to = today.toISOString().slice(0, 10);
        const [dash, series, transactions] = await Promise.all([
          mobileDashboardApiClient.getDashboard(),
          mobileDashboardApiClient.getAssetStatistics(from, to).catch(() => null),
          mobileTransactionApiClient.listTransactions(5).catch(() => null),
        ]);
        if (cancelled) return;
        setDashboard(dash.data);
        const points = series?.data.points ?? [];
        const first = points[0]?.values.netAssets;
        const last = points[points.length - 1]?.values.netAssets;
        if (first && last) setTrend({ first, last });
        setRecent(transactions?.data ?? []);
      } catch {
        if (!cancelled) setMessage('无法加载总览：网络或服务暂不可用。');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <View className="mt-4 gap-2" testID="home-dashboard-loading">
        <Text className="text-sm text-muted-light dark:text-muted-dark">正在加载核心指标…</Text>
      </View>
    );
  }

  if (!dashboard) {
    return (
      <View className="my-4 gap-2" testID="home-dashboard-error">
        <Text accessibilityRole="alert" className="text-sm text-destructive">{message ?? '总览暂不可用。'}</Text>
        <Pressable accessibilityRole="button" onPress={onOpenQuickRecord} testID="home-open-quick-record">
          <Text className="font-semibold text-accent">去记一笔</Text>
        </Pressable>
      </View>
    );
  }

  const metrics = [
    { label: '总资产', value: dashboard.summary.totalAssets },
    { label: '可用资金', value: dashboard.summary.availableFunds },
    { label: '投资资产', value: dashboard.summary.investmentAssets },
    { label: '总负债', value: dashboard.summary.totalLiabilities },
    { label: '净资产', value: dashboard.summary.netAssets },
  ];
  const summary = trend ? `${trend.first} → ${trend.last}` : null;

  return (
    <View className="mt-4 gap-3" testID="home-dashboard">
      <View className="flex-row flex-wrap gap-2">
        {metrics.map((metric) => (
          <View key={metric.label} className="min-w-[45%] flex-1 rounded-xl bg-surface-light p-3 dark:bg-surface-dark" testID={`metric-${metric.label}`}>
            <Text className="text-xs text-muted-light dark:text-muted-dark">{metric.label}</Text>
            <Text className="text-lg font-bold text-ink-light dark:text-ink-dark">
              {metric.value} {dashboard.baseCurrency}
            </Text>
          </View>
        ))}
      </View>

      {dashboard.dataQualityWarnings.length > 0 ? (
        <View className="gap-1 rounded-xl border border-amber-500/40 p-3" testID="home-quality-warnings">
          {dashboard.dataQualityWarnings.map((warning) => (
            <Text key={warning.code} accessibilityRole="alert" className="text-sm text-amber-600 dark:text-amber-400">
              {WARNING_LABELS[warning.code] ?? warning.code}（{warning.affectedCount} 项未计入）
            </Text>
          ))}
        </View>
      ) : null}

      <View className="gap-1">
        <Text className="text-sm text-muted-light dark:text-muted-dark">
          数据截至 {dashboard.asOf} · 变更序列 {dashboard.asOfSequence}
        </Text>
        {summary ? <Text className="text-sm text-muted-light dark:text-muted-dark" testID="home-trend-summary">净资产趋势：{summary}</Text> : null}
      </View>

      {recent.length > 0 ? (
        <View className="gap-1">
          <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">近期流水</Text>
          {recent.map((transaction) => (
            <Text key={transaction.id} className="text-xs text-muted-light dark:text-muted-dark">
              {transaction.businessDate} · {TYPE_LABELS[transaction.type] ?? transaction.type} · {transaction.status}
            </Text>
          ))}
        </View>
      ) : null}

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="快速记账"
        onPress={onOpenQuickRecord}
        testID="home-quick-record"
        className="min-h-11 items-center justify-center rounded-lg bg-accent active:opacity-70"
      >
        <Text className="font-bold text-canvas-dark">快速记账</Text>
      </Pressable>
    </View>
  );
}
