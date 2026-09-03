import { useEffect, useState } from 'react';
import { Modal, Pressable, Text, View } from 'react-native';

import type {
  Instrument,
  InvestmentReturnCalendar,
  InvestmentReturnDay,
  InvestmentReturnDayDetails,
  MobileInvestmentApiClient,
} from '@/api/api-client';

const STATUS_LABELS: Record<InvestmentReturnDay['status'], string> = {
  CALCULATED: '已计算',
  NON_TRADING_DAY: '非交易日',
  NO_POSITION: '无持仓',
  PENDING_DATA: '待数据',
  PARTIAL: '部分估值',
  UNPRICED: '无法估值',
};

const SUMMARY_STATUS_LABELS: Record<InvestmentReturnCalendar['summaryStatus'], string> = {
  COMPLETE: '完整计算',
  PENDING: '待数据',
  PARTIAL: '部分估值',
  UNAVAILABLE: '暂不可用',
};

type CalendarMode = 'amount' | 'rate';

export interface InvestmentReturnCalendarProps {
  api: MobileInvestmentApiClient;
  selectedInstrument: Instrument | null;
  initialMonth?: string;
}

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7);
}

function shiftMonth(month: string, offset: number): string {
  const [year, monthNumber] = month.split('-').map(Number);
  const shifted = new Date(Date.UTC(year, monthNumber - 1 + offset, 1));
  return `${shifted.getUTCFullYear()}-${String(shifted.getUTCMonth() + 1).padStart(2, '0')}`;
}

function monthLabel(month: string): string {
  const [year, monthNumber] = month.split('-');
  return `${year} 年 ${monthNumber} 月`;
}

function money(value: string | null, currency: string): string {
  return value === null ? '—' : `${value} ${currency}`;
}

function rate(value: string | null): string {
  return value === null ? '—' : `${value}%`;
}

function dayValue(day: InvestmentReturnDay, mode: CalendarMode, currency: string): string {
  return mode === 'amount' ? money(day.dailyProfit, currency) : rate(day.dailyReturnRate);
}

function detailValue(value: string | null, currency: string): string {
  return money(value, currency);
}

export function InvestmentReturnCalendar({ api, selectedInstrument, initialMonth }: InvestmentReturnCalendarProps) {
  const [scopeType, setScopeType] = useState<'PORTFOLIO' | 'INSTRUMENT'>('PORTFOLIO');
  const [mode, setMode] = useState<CalendarMode>('amount');
  const [month, setMonth] = useState(initialMonth ?? currentMonth());
  const [calendar, setCalendar] = useState<InvestmentReturnCalendar | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [selectedDay, setSelectedDay] = useState<InvestmentReturnDay | null>(null);
  const [dayDetails, setDayDetails] = useState<InvestmentReturnDayDetails | null>(null);
  const [dayDetailsLoading, setDayDetailsLoading] = useState(false);
  const [dayDetailsMessage, setDayDetailsMessage] = useState<string | null>(null);

  const instrumentId = scopeType === 'INSTRUMENT' ? selectedInstrument?.id : undefined;

  useEffect(() => {
    let cancelled = false;
    setSelectedDay(null);
    setDayDetails(null);
    setMessage(null);
    if (scopeType === 'INSTRUMENT' && !instrumentId) {
      setCalendar(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    void api.getInvestmentReturnCalendar(month, scopeType, instrumentId)
      .then((envelope) => {
        if (!cancelled) setCalendar(envelope.data);
      })
      .catch(() => {
        if (!cancelled) {
          setCalendar(null);
          setMessage('无法加载收益月历：网络或服务暂不可用。');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api, instrumentId, month, scopeType]);

  async function openDay(day: InvestmentReturnDay): Promise<void> {
    setSelectedDay(day);
    setDayDetails(null);
    setDayDetailsMessage(null);
    setDayDetailsLoading(true);
    try {
      const envelope = await api.getInvestmentReturnDayDetails(day.businessDate, scopeType, instrumentId);
      setDayDetails(envelope.data);
    } catch {
      setDayDetailsMessage('无法加载日期明细，可关闭后重试。');
    } finally {
      setDayDetailsLoading(false);
    }
  }

  function closeDay(): void {
    setSelectedDay(null);
    setDayDetails(null);
    setDayDetailsMessage(null);
  }

  return (
    <View className="gap-3" testID="investment-return-calendar">
      <View className="flex-row items-center justify-between">
        <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">投资收益月历</Text>
        <View className="flex-row gap-2">
          <Pressable
            accessibilityLabel="显示收益金额"
            accessibilityRole="button"
            accessibilityState={{ selected: mode === 'amount' }}
            onPress={() => setMode('amount')}
            testID="investment-calendar-mode-amount"
            className={`min-h-9 justify-center rounded-lg border px-3 ${mode === 'amount' ? 'border-accent bg-accent' : 'border-accent/40'}`}
          >
            <Text className={mode === 'amount' ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>金额</Text>
          </Pressable>
          <Pressable
            accessibilityLabel="显示收益率"
            accessibilityRole="button"
            accessibilityState={{ selected: mode === 'rate' }}
            onPress={() => setMode('rate')}
            testID="investment-calendar-mode-rate"
            className={`min-h-9 justify-center rounded-lg border px-3 ${mode === 'rate' ? 'border-accent bg-accent' : 'border-accent/40'}`}
          >
            <Text className={mode === 'rate' ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>收益率</Text>
          </Pressable>
        </View>
      </View>

      <View className="flex-row gap-2" accessibilityRole="tablist">
        <Pressable
          accessibilityLabel="全部投资"
          accessibilityRole="tab"
          accessibilityState={{ selected: scopeType === 'PORTFOLIO' }}
          onPress={() => setScopeType('PORTFOLIO')}
          testID="investment-calendar-scope-portfolio"
          className={`min-h-11 flex-1 items-center justify-center rounded-lg border ${scopeType === 'PORTFOLIO' ? 'border-accent bg-accent' : 'border-accent/40'}`}
        >
          <Text className={scopeType === 'PORTFOLIO' ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>全部投资</Text>
        </Pressable>
        <Pressable
          accessibilityLabel="单一标的"
          accessibilityRole="tab"
          accessibilityState={{ selected: scopeType === 'INSTRUMENT' }}
          onPress={() => setScopeType('INSTRUMENT')}
          testID="investment-calendar-scope-instrument"
          className={`min-h-11 flex-1 items-center justify-center rounded-lg border ${scopeType === 'INSTRUMENT' ? 'border-accent bg-accent' : 'border-accent/40'}`}
        >
          <Text className={scopeType === 'INSTRUMENT' ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>单一标的</Text>
        </Pressable>
      </View>

      {scopeType === 'INSTRUMENT' ? (
        <View className="rounded-lg border border-accent/30 p-3">
          <Text className="text-sm text-muted-light dark:text-muted-dark">当前标的</Text>
          <Text className="mt-1 text-base font-semibold text-ink-light dark:text-ink-dark">
            {selectedInstrument ? `${selectedInstrument.name} · ${selectedInstrument.market}` : '请先在上方搜索并选择股票、基金或 ETF'}
          </Text>
        </View>
      ) : null}

      <View className="flex-row items-center justify-between">
        <Pressable
          accessibilityLabel="上个月"
          accessibilityRole="button"
          onPress={() => setMonth((value) => shiftMonth(value, -1))}
          testID="investment-calendar-previous-month"
          className="min-h-11 min-w-11 items-center justify-center rounded-lg border border-accent/40"
        >
          <Text className="text-lg text-ink-light dark:text-ink-dark">‹</Text>
        </Pressable>
        <Text className="text-base font-semibold text-ink-light dark:text-ink-dark" testID="investment-calendar-month">{monthLabel(month)}</Text>
        <Pressable
          accessibilityLabel="下个月"
          accessibilityRole="button"
          onPress={() => setMonth((value) => shiftMonth(value, 1))}
          testID="investment-calendar-next-month"
          className="min-h-11 min-w-11 items-center justify-center rounded-lg border border-accent/40"
        >
          <Text className="text-lg text-ink-light dark:text-ink-dark">›</Text>
        </Pressable>
      </View>

      <Text className="text-xs leading-5 text-muted-light dark:text-muted-dark">
        收益率采用服务端 Modified Dietz 口径；转入转出与买卖本金不计收益，手续费和税费只扣减一次。
      </Text>

      {message ? <Text accessibilityRole="alert" testID="investment-calendar-message" className="text-sm text-destructive">{message}</Text> : null}
      {scopeType === 'INSTRUMENT' && !instrumentId ? (
        <Text testID="investment-calendar-instrument-empty" className="text-sm text-muted-light dark:text-muted-dark">选择标的后才能查询单一标的收益。</Text>
      ) : loading ? (
        <Text className="text-sm text-muted-light dark:text-muted-dark">正在加载收益月历…</Text>
      ) : calendar ? (
        <CalendarContent
          calendar={calendar}
          mode={mode}
          onOpenDay={(day) => void openDay(day)}
        />
      ) : (
        <Text className="text-sm text-muted-light dark:text-muted-dark">当前月份收益月历暂不可用。</Text>
      )}

      <ReturnDayBottomSheet
        day={selectedDay}
        details={dayDetails}
        loading={dayDetailsLoading}
        message={dayDetailsMessage}
        onClose={closeDay}
      />
    </View>
  );
}

function CalendarContent({
  calendar,
  mode,
  onOpenDay,
}: {
  calendar: InvestmentReturnCalendar;
  mode: CalendarMode;
  onOpenDay: (day: InvestmentReturnDay) => void;
}) {
  return (
    <View className="gap-3" testID="investment-calendar-content">
      <View className="flex-row flex-wrap gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-calendar-summary">
        <SummaryItem label="月度收益" value={money(calendar.monthlyProfit, calendar.baseCurrency)} />
        <SummaryItem label="月度收益率" value={rate(calendar.monthlyReturnRate)} />
        <SummaryItem label="完整性" value={SUMMARY_STATUS_LABELS[calendar.summaryStatus]} />
        <SummaryItem label="收益日" value={`${calendar.profitDayCount} 天`} />
        <SummaryItem label="亏损日" value={`${calendar.lossDayCount} 天`} />
        <SummaryItem label="真实零收益" value={`${calendar.zeroDayCount} 天`} />
      </View>

      <View className="flex-row flex-wrap gap-2" accessible accessibilityLabel={`${calendar.month} 投资收益日历`}>
        {calendar.days.map((day) => (
          <Pressable
            key={day.businessDate}
            accessibilityLabel={`${day.businessDate}，${STATUS_LABELS[day.status]}，${mode === 'amount' ? '收益金额' : '收益率'}，${dayValue(day, mode, calendar.baseCurrency)}`}
            accessibilityRole="button"
            onPress={() => onOpenDay(day)}
            testID={`investment-return-day-${day.businessDate}`}
            className="min-h-24 min-w-[30%] flex-1 rounded-xl border border-accent/30 bg-surface-light p-3 dark:bg-surface-dark"
          >
            <Text className="text-xs font-semibold text-ink-light dark:text-ink-dark">{day.businessDate}</Text>
            <Text className="mt-2 text-sm font-bold text-ink-light dark:text-ink-dark">{dayValue(day, mode, calendar.baseCurrency)}</Text>
            <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">{STATUS_LABELS[day.status]}</Text>
            {day.missingInstrumentCount > 0 ? (
              <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">缺估值 {day.missingInstrumentCount} 项</Text>
            ) : null}
          </Pressable>
        ))}
      </View>

      <View className="gap-1 rounded-lg border border-accent/30 p-3">
        <Text className="text-xs text-muted-light dark:text-muted-dark">数据截至 {calendar.asOf}</Text>
        <Text className="text-xs text-muted-light dark:text-muted-dark">最近重算 {calendar.recalculatedAt} · 估值修订 v{calendar.valuationRevision}</Text>
        <Text className="text-xs leading-5 text-muted-light dark:text-muted-dark">日期状态：已计算、非交易日、无持仓、待数据、部分估值、无法估值。</Text>
      </View>

      {calendar.dataQualityWarnings.length > 0 ? (
        <View className="gap-1 rounded-lg border border-amber-500/40 p-3" testID="investment-calendar-quality-warnings">
          {calendar.dataQualityWarnings.map((warning) => (
            <Text key={warning.code} accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">
              数据质量提示：{warning.code}（{warning.affectedCount} 项）
            </Text>
          ))}
        </View>
      ) : null}
    </View>
  );
}

function SummaryItem({ label, value }: { label: string; value: string }) {
  return (
    <View className="min-w-[30%] flex-1">
      <Text className="text-xs text-muted-light dark:text-muted-dark">{label}</Text>
      <Text className="mt-1 text-sm font-semibold text-ink-light dark:text-ink-dark">{value}</Text>
    </View>
  );
}

function ReturnDayBottomSheet({
  day,
  details,
  loading,
  message,
  onClose,
}: {
  day: InvestmentReturnDay | null;
  details: InvestmentReturnDayDetails | null;
  loading: boolean;
  message: string | null;
  onClose: () => void;
}) {
  const currency = details?.baseCurrency ?? 'CNY';
  return (
    <Modal
      visible={day !== null}
      transparent
      animationType="slide"
      onRequestClose={onClose}
      accessibilityViewIsModal
    >
      <View className="flex-1 justify-end bg-black/30">
        <View className="max-h-[86%] rounded-t-3xl bg-surface-light p-5 dark:bg-surface-dark" testID="investment-return-day-sheet">
          <View className="flex-row items-start justify-between">
            <View className="flex-1">
              <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">{day?.businessDate} 收益明细</Text>
              <Text className="mt-1 text-sm text-muted-light dark:text-muted-dark">状态：{day ? STATUS_LABELS[day.status] : '—'}</Text>
            </View>
            <Pressable accessibilityLabel="关闭收益明细" accessibilityRole="button" onPress={onClose} testID="investment-return-day-close" className="min-h-11 min-w-11 items-center justify-center rounded-lg border border-accent/40">
              <Text className="text-lg text-ink-light dark:text-ink-dark">×</Text>
            </Pressable>
          </View>

          {loading ? <Text className="mt-5 text-sm text-muted-light dark:text-muted-dark">正在加载日期明细…</Text> : null}
          {message ? <Text accessibilityRole="alert" className="mt-5 text-sm text-destructive">{message}</Text> : null}
          {details ? (
            <View className="mt-5 gap-3">
              <Text className="text-sm leading-5 text-ink-light dark:text-ink-dark">
                {details.status === 'CALCULATED'
                  ? `当日收益 ${detailValue(details.dailyProfit, currency)} · 收益率 ${rate(details.dailyReturnRate)}`
                  : `完整收益暂不提供：${STATUS_LABELS[details.status]}。不以部分估值或 0 代替。`}
              </Text>
              <View className="flex-row flex-wrap gap-2">
                <DetailItem label="日初市值" value={detailValue(details.beginValue, currency)} />
                <DetailItem label="日终市值" value={detailValue(details.endValue, currency)} />
                <DetailItem label="净现金流" value={detailValue(details.netCashFlow, currency)} />
                <DetailItem label="价格影响" value={detailValue(details.marketEffect, currency)} />
                <DetailItem label="汇率影响" value={detailValue(details.fxEffect, currency)} />
                <DetailItem label="分红" value={detailValue(details.dividends, currency)} />
                <DetailItem label="手续费" value={detailValue(details.fees, currency)} />
                <DetailItem label="税费" value={detailValue(details.taxes, currency)} />
              </View>
              {details.contributions.length > 0 ? (
                <View className="gap-2">
                  <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">收益贡献</Text>
                  {details.contributions.map((contribution, index) => (
                    <View key={`${contribution.instrumentId ?? contribution.contributionType}-${index}`} className="rounded-lg border border-accent/30 p-3">
                      <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{contribution.label}</Text>
                      <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
                        {contribution.contributionType} · {contribution.status} · 收益 {detailValue(contribution.profit, currency)} · 收益率 {rate(contribution.returnRate)}
                      </Text>
                      <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">价格日期：{contribution.priceAsOf ?? '暂无'}</Text>
                    </View>
                  ))}
                </View>
              ) : null}
              {details.dataQualityWarnings.length > 0 ? (
                <View className="gap-1 rounded-lg border border-amber-500/40 p-3">
                  {details.dataQualityWarnings.map((warning) => (
                    <Text key={warning.code} accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">
                      数据质量提示：{warning.code}（{warning.affectedCount} 项）
                    </Text>
                  ))}
                </View>
              ) : null}
              <Text className="text-xs leading-5 text-muted-light dark:text-muted-dark">
                数据截至 {details.asOf} · 估值修订 v{details.valuationRevision}。金额和收益均由服务端计算。
              </Text>
            </View>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <View className="min-w-[45%] flex-1 rounded-lg bg-canvas-light p-3 dark:bg-canvas-dark">
      <Text className="text-xs text-muted-light dark:text-muted-dark">{label}</Text>
      <Text className="mt-1 text-sm font-semibold text-ink-light dark:text-ink-dark">{value}</Text>
    </View>
  );
}
