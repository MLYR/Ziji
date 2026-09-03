import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import type {
  Account,
  AccountBalance,
  Instrument,
  InvestmentPerformance,
  MobileAccountsApiClient,
  MobileInvestmentApiClient,
  Position,
} from '@/api/api-client';

const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  BROKERAGE: '券商',
  FUND: '基金账户',
  OTHER: '其他投资',
};

const INSTRUMENT_TYPE_LABELS: Record<string, string> = {
  STOCK: '股票',
  FUND: '基金',
  ETF: 'ETF',
  OTHER: '其他证券',
};

const XIRR_STATUS_LABELS: Record<InvestmentPerformance['xirrStatus'], string> = {
  AVAILABLE: '可用',
  INSUFFICIENT_CASH_FLOWS: '现金流不足',
  UNPRICED: '存在未估值',
};

export interface InvestmentDetailScreenProps {
  accountId: string | null;
  api: MobileInvestmentApiClient;
  accountsApi: MobileAccountsApiClient;
}

/** 投资详情只展示服务端重建的持仓、成本、估值和收益，不在 Mobile 端重算。 */
export function InvestmentDetailScreen({ accountId, api, accountsApi }: InvestmentDetailScreenProps) {
  const [account, setAccount] = useState<Account | null>(null);
  const [balance, setBalance] = useState<AccountBalance | null>(null);
  const [positions, setPositions] = useState<Position[] | null>(null);
  const [performance, setPerformance] = useState<InvestmentPerformance | null>(null);
  const [instruments, setInstruments] = useState<Record<string, Instrument>>({});
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!accountId) {
      setLoading(false);
      setMessage('缺少投资账户 ID。');
      return;
    }
    setLoading(true);
    setMessage(null);
    void Promise.all([
      accountsApi.getAccount(accountId),
      accountsApi.getAccountBalance(accountId).catch(() => null),
      api.listInvestmentPositions(accountId, { limit: 100 }),
      api.getInvestmentPerformance(accountId),
    ])
      .then(async ([accountEnvelope, balanceEnvelope, positionsEnvelope, performanceEnvelope]) => {
        if (cancelled) return;
        setAccount(accountEnvelope.data);
        setBalance(balanceEnvelope?.data ?? null);
        setPositions(positionsEnvelope.data);
        setPerformance(performanceEnvelope.data);
        const loadedInstruments = await Promise.all(positionsEnvelope.data.map(async (position) => {
          try {
            return await api.getInstrument(position.instrumentId);
          } catch {
            return null;
          }
        }));
        if (cancelled) return;
        setInstruments(Object.fromEntries(
          loadedInstruments.filter((envelope): envelope is NonNullable<typeof envelope> => envelope !== null).map((envelope) => [envelope.data.id, envelope.data]),
        ));
      })
      .catch(() => {
        if (!cancelled) {
          setAccount(null);
          setBalance(null);
          setPositions(null);
          setPerformance(null);
          setMessage('无法加载投资账户详情：网络或服务暂不可用。');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [accountId, accountsApi, api]);

  if (loading) {
    return <View className="p-4" accessibilityLiveRegion="polite"><Text className="text-sm text-muted-light dark:text-muted-dark">正在加载投资账户详情…</Text></View>;
  }

  if (!account) {
    return <View className="p-4" testID="investment-detail-error"><Text accessibilityRole="alert" className="text-sm text-destructive">{message ?? '投资账户详情暂不可用。'}</Text></View>;
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }} testID="investment-detail-screen">
      {message ? <Text accessibilityRole="alert" testID="investment-detail-message" className="text-sm text-destructive">{message}</Text> : null}
      <View className="gap-1">
        <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">{account.name}</Text>
        <Text className="text-sm text-muted-light dark:text-muted-dark">{ACCOUNT_TYPE_LABELS[account.accountType] ?? account.accountType} · {account.currency}</Text>
      </View>

      {balance ? (
        <View className="gap-1 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-detail-cash">
          <Text className="text-sm text-muted-light dark:text-muted-dark">券商现金</Text>
          <Text className="text-xl font-bold text-ink-light dark:text-ink-dark">{balance.ledgerBalance} {balance.currency}</Text>
          <Text className="text-xs text-muted-light dark:text-muted-dark">可用 {balance.availableBalance} · 不可用 {balance.unavailableAmount}</Text>
        </View>
      ) : <Text className="text-sm text-muted-light dark:text-muted-dark">券商现金暂不可用。</Text>}

      <View className="gap-3" testID="investment-detail-positions">
        <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">当前持仓</Text>
        {positions === null ? (
          <Text className="text-sm text-muted-light dark:text-muted-dark">持仓暂不可用。</Text>
        ) : positions.length === 0 ? (
          <Text className="text-sm text-muted-light dark:text-muted-dark">当前没有有效持仓。</Text>
        ) : (
          positions.map((position) => <PositionCard key={position.instrumentId} position={position} accountCurrency={account.currency} instrument={instruments[position.instrumentId]} />)
        )}
      </View>

      {performance ? <PerformanceCard performance={performance} /> : <Text className="text-sm text-muted-light dark:text-muted-dark">投资收益暂不可用。</Text>}
    </ScrollView>
  );
}

function PositionCard({
  position,
  accountCurrency,
  instrument,
}: {
  position: Position;
  accountCurrency: string;
  instrument?: Instrument;
}) {
  const name = instrument?.name ?? position.instrumentId;
  const type = instrument ? `${INSTRUMENT_TYPE_LABELS[instrument.instrumentType] ?? instrument.instrumentType} · ${instrument.market}` : '产品详情暂不可用';
  return (
    <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID={`investment-position-${position.instrumentId}`}>
      <View className="flex-row items-start justify-between gap-2">
        <View className="flex-1">
          <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{name}</Text>
          <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">{type}</Text>
        </View>
        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{position.valuationStatus === 'PRICED' ? '已估值' : '未估值'}</Text>
      </View>
      <PositionValue label="持仓数量" value={position.quantity} />
      <PositionValue label="持仓成本" value={`${position.costBasis} ${accountCurrency}`} />
      <PositionValue label="移动平均成本" value={position.averageCost} />
      <PositionValue label="当前价格" value={position.marketPrice ?? '—'} />
      <PositionValue label="当前市值" value={position.marketValue === null ? '—' : `${position.marketValue} ${accountCurrency}`} />
      <PositionValue label="未实现收益" value={position.unrealizedProfit === null ? '—' : `${position.unrealizedProfit} ${accountCurrency}`} />
      <Text className="text-xs text-muted-light dark:text-muted-dark">价格日期：{position.priceAsOf ?? '暂无有效价格日期'}</Text>
      {position.valuationStatus === 'UNPRICED' ? (
        <Text accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">该持仓缺少有效价格，市值和未实现收益保持为空，未按 0 处理。</Text>
      ) : null}
    </View>
  );
}

function PositionValue({ label, value }: { label: string; value: string }) {
  return (
    <View className="flex-row items-center justify-between gap-3">
      <Text className="text-sm text-muted-light dark:text-muted-dark">{label}</Text>
      <Text className="flex-1 text-right text-sm font-semibold text-ink-light dark:text-ink-dark">{value}</Text>
    </View>
  );
}

function PerformanceCard({ performance }: { performance: InvestmentPerformance }) {
  return (
    <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-detail-performance">
      <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">投资收益</Text>
      <PerformanceValue label="已实现收益" value={`${performance.realizedProfit} ${performance.currency}`} />
      <PerformanceValue label="未实现收益" value={`${performance.unrealizedProfit} ${performance.currency}`} />
      <PerformanceValue label="分红" value={`${performance.dividends} ${performance.currency}`} />
      <PerformanceValue label="手续费" value={`${performance.fees} ${performance.currency}`} />
      <PerformanceValue label="税费" value={`${performance.taxes} ${performance.currency}`} />
      <PerformanceValue label="年化收益 XIRR" value={performance.xirr === null ? '—' : `${performance.xirr}%`} />
      <Text className="text-sm text-muted-light dark:text-muted-dark">XIRR 状态：{XIRR_STATUS_LABELS[performance.xirrStatus]}</Text>
      {performance.xirrStatus !== 'AVAILABLE' ? (
        <Text accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">XIRR 当前不可用，保留服务端状态，不以 0% 代替。</Text>
      ) : null}
    </View>
  );
}

function PerformanceValue({ label, value }: { label: string; value: string }) {
  return (
    <View className="flex-row items-center justify-between gap-3">
      <Text className="text-sm text-muted-light dark:text-muted-dark">{label}</Text>
      <Text className="flex-1 text-right text-sm font-semibold text-ink-light dark:text-ink-dark">{value}</Text>
    </View>
  );
}
