import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import type {
  Account,
  Currency,
  CreateInstrumentRequest,
  CreateInvestmentTradeRequest,
  Instrument,
  InvestmentOverview,
  MarketDataStatusEnvelope,
  MobileAccountsApiClient,
  MobileInvestmentApiClient,
} from '@/api/api-client';
import { InvestmentReturnCalendar } from '@/investments/investment-return-calendar';

const INSTRUMENT_TYPE_LABELS: Record<Instrument['instrumentType'], string> = {
  STOCK: '股票',
  FUND: '基金',
  ETF: 'ETF',
  OTHER: '其他证券',
};

const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  BROKERAGE: '券商',
  FUND: '基金账户',
  OTHER: '其他投资',
};

const MARKET_STATUS_LABELS: Record<MarketDataStatusEnvelope['data']['status'], string> = {
  AVAILABLE: '可用',
  DEGRADED: '降级',
  UNAVAILABLE: '不可用',
};

const FRESHNESS_LABELS: Record<MarketDataStatusEnvelope['data']['freshness'], string> = {
  FRESH: '新鲜',
  STALE: '已过期',
  UNAVAILABLE: '暂无',
};

const TRADE_SIDE_LABELS: Record<CreateInvestmentTradeRequest['side'], string> = {
  BUY: '买入',
  SELL: '卖出',
  DIVIDEND: '分红',
};

const CURRENCIES = ['CNY', 'USD', 'HKD', 'JPY', 'EUR'] as const;

type TradeSide = CreateInvestmentTradeRequest['side'];

export interface InvestmentScreenProps {
  api: MobileInvestmentApiClient;
  accountsApi: Pick<MobileAccountsApiClient, 'listAccounts'>;
  onOpenAccount: (accountId: string) => void;
  onOpenAccounts?: () => void;
  initialMonth?: string;
}

/** 投资主页面只保存当前 UI 草稿；金额、持仓、收益和行情结论始终来自服务端响应。 */
export function InvestmentScreen({ api, accountsApi, onOpenAccount, onOpenAccounts, initialMonth }: InvestmentScreenProps) {
  const [overview, setOverview] = useState<InvestmentOverview | null>(null);
  const [marketStatus, setMarketStatus] = useState<MarketDataStatusEnvelope['data'] | null>(null);
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadMessage, setLoadMessage] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Instrument[] | null>(null);
  const [selectedInstrument, setSelectedInstrument] = useState<Instrument | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchMessage, setSearchMessage] = useState<string | null>(null);
  const [manualFormOpen, setManualFormOpen] = useState(false);
  const [manualName, setManualName] = useState('');
  const [manualType, setManualType] = useState<CreateInstrumentRequest['instrumentType']>('OTHER');
  const [manualCurrency, setManualCurrency] = useState<Currency>('CNY');
  const [manualSubmitting, setManualSubmitting] = useState(false);
  const [manualMessage, setManualMessage] = useState<string | null>(null);

  const [tradeSide, setTradeSide] = useState<TradeSide>('BUY');
  const [tradeAccountId, setTradeAccountId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [unitPrice, setUnitPrice] = useState('');
  const [dividendAmount, setDividendAmount] = useState('');
  const [feeAmount, setFeeAmount] = useState('0.00');
  const [taxAmount, setTaxAmount] = useState('0.00');
  const [tradeAt, setTradeAt] = useState(() => new Date().toISOString());
  const [tradeSubmitting, setTradeSubmitting] = useState(false);
  const [tradeMessage, setTradeMessage] = useState<string | null>(null);
  const tradeIdempotencyKey = useRef<string | null>(null);
  const manualIdempotencyKey = useRef<string | null>(null);

  async function loadInvestmentData(isActive: () => boolean = () => true): Promise<void> {
    setLoading(true);
    setLoadMessage(null);
    try {
      const [overviewEnvelope, statusEnvelope, accountsEnvelope] = await Promise.all([
        api.getInvestmentOverview(),
        api.getMarketDataStatus().catch(() => null),
        accountsApi.listAccounts(100).catch(() => null),
      ]);
      if (!isActive()) return;
      setOverview(overviewEnvelope.data);
      setMarketStatus(statusEnvelope?.data ?? null);
      setAccounts(accountsEnvelope?.data ?? null);
      const warnings: string[] = [];
      if (!statusEnvelope) warnings.push('行情状态暂不可用');
      if (!accountsEnvelope) warnings.push('投资账户列表暂不可用');
      setLoadMessage(warnings.length > 0 ? `${warnings.join('；')}。` : null);
    } catch {
      if (!isActive()) return;
      setOverview(null);
      setMarketStatus(null);
      setAccounts(null);
      setLoadMessage('无法加载投资概览：网络或服务暂不可用。');
    } finally {
      if (isActive()) setLoading(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    void loadInvestmentData(() => !cancelled);
    return () => {
      cancelled = true;
    };
  }, [accountsApi, api]);

  const investmentAccounts = (accounts ?? []).filter((account) => account.accountClass === 'INVESTMENT' && account.status === 'ACTIVE');
  const selectedAccount = investmentAccounts.find((account) => account.id === tradeAccountId) ?? null;
  const tradeCurrency = selectedAccount?.currency ?? selectedInstrument?.currency ?? 'CNY';

  function selectInstrument(instrument: Instrument): void {
    setSelectedInstrument(instrument);
    setTradeMessage(null);
    tradeIdempotencyKey.current = null;
  }

  async function searchProducts(): Promise<void> {
    const query = searchQuery.trim();
    if (!query) {
      setSearchMessage('请输入产品名称或代码。');
      return;
    }
    setSearching(true);
    setSearchMessage(null);
    try {
      const envelope = await api.searchInstruments(query, 20);
      setSearchResults(envelope.data);
      if (envelope.data.length === 0) setSearchMessage('没有匹配产品，可以创建手工产品。');
    } catch {
      setSearchResults(null);
      setSearchMessage('产品搜索失败，可检查网络后重试。');
    } finally {
      setSearching(false);
    }
  }

  async function createManualInstrument(): Promise<void> {
    if (!manualName.trim()) {
      setManualMessage('请填写产品名称。');
      return;
    }
    setManualSubmitting(true);
    setManualMessage(null);
    manualIdempotencyKey.current ??= globalThis.crypto.randomUUID();
    try {
      const envelope = await api.createInstrument(manualIdempotencyKey.current, {
        instrumentType: manualType,
        name: manualName.trim(),
        market: 'MANUAL',
        currency: manualCurrency,
      });
      manualIdempotencyKey.current = null;
      setSelectedInstrument(envelope.data);
      setSearchResults([envelope.data]);
      setManualFormOpen(false);
      setManualName('');
      setManualMessage(null);
      setSearchMessage('手工产品已创建并选中。');
    } catch {
      // 失败保留相同载荷和 Idempotency-Key，用户可以安全原样重试。
      setManualMessage('手工产品创建失败，可原样重试。');
    } finally {
      setManualSubmitting(false);
    }
  }

  function tradeValidationMessage(): string | null {
    if (!tradeAccountId) return '请选择投资账户。';
    if (!selectedInstrument) return '请先搜索并选择产品。';
    if (!tradeAt.trim()) return '请填写交易时间。';
    if (!feeAmount.trim() || !taxAmount.trim()) return '手续费和税费不能为空，没有费用请填写 0。';
    if (tradeSide === 'DIVIDEND' && !dividendAmount.trim()) return '请填写分红金额。';
    if (tradeSide !== 'DIVIDEND' && (!quantity.trim() || !unitPrice.trim())) return '请填写数量和成交价格。';
    return null;
  }

  async function submitTrade(): Promise<void> {
    const validationMessage = tradeValidationMessage();
    if (validationMessage) {
      setTradeMessage(validationMessage);
      return;
    }
    const body: CreateInvestmentTradeRequest = {
      side: tradeSide,
      investmentAccountId: tradeAccountId,
      instrumentId: selectedInstrument?.id ?? '',
      currency: tradeCurrency,
      feeAmount: feeAmount.trim(),
      taxAmount: taxAmount.trim(),
      tradeAt: tradeAt.trim(),
    };
    if (tradeSide === 'DIVIDEND') {
      body.dividendAmount = dividendAmount.trim();
    } else {
      body.quantity = quantity.trim();
      body.unitPrice = unitPrice.trim();
    }

    setTradeSubmitting(true);
    setTradeMessage(null);
    tradeIdempotencyKey.current ??= globalThis.crypto.randomUUID();
    try {
      await api.createInvestmentTrade(tradeIdempotencyKey.current, body);
      tradeIdempotencyKey.current = null;
      setTradeMessage('投资交易已保存，持仓与收益将以服务端最新结果为准。');
      setQuantity('');
      setUnitPrice('');
      setDividendAmount('');
    } catch {
      // 同一载荷保留幂等键，服务端超时或网络失败时可原样重试。
      setTradeMessage('投资交易保存失败，可原样重试。');
    } finally {
      setTradeSubmitting(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }} testID="investment-screen" keyboardShouldPersistTaps="handled">
      <View className="flex-row items-center justify-between">
        <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">投资</Text>
        <Pressable
          accessibilityLabel={loading ? '正在刷新投资数据' : '刷新投资数据'}
          accessibilityRole="button"
          accessibilityState={{ busy: loading, disabled: loading }}
          disabled={loading}
          onPress={() => void loadInvestmentData()}
          testID="investment-refresh"
          className={`min-h-11 items-center justify-center rounded-lg border border-accent px-3 ${loading ? 'opacity-50' : 'active:opacity-70'}`}
        >
          <Text className="font-semibold text-ink-light dark:text-ink-dark">{loading ? '刷新中…' : '刷新'}</Text>
        </Pressable>
      </View>

      {loadMessage ? <Text accessibilityRole="alert" testID="investment-load-message" className="text-sm text-destructive">{loadMessage}</Text> : null}
      {loading && !overview ? <Text className="text-sm text-muted-light dark:text-muted-dark">正在加载投资概览…</Text> : null}

      {overview ? <InvestmentOverviewCard overview={overview} /> : null}
      <MarketQualityCard status={marketStatus} />

      <View className="gap-3" testID="investment-accounts">
        <View className="flex-row items-center justify-between">
          <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">投资账户与持仓</Text>
          {onOpenAccounts ? (
            <Pressable accessibilityLabel="查看全部账户" accessibilityRole="button" onPress={onOpenAccounts} testID="investment-open-accounts" className="min-h-11 justify-center px-2">
              <Text className="font-semibold text-accent">账户管理</Text>
            </Pressable>
          ) : null}
        </View>
        {accounts === null ? (
          <Text className="text-sm text-muted-light dark:text-muted-dark">投资账户暂不可用。</Text>
        ) : investmentAccounts.length === 0 ? (
          <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
            <Text className="text-sm text-muted-light dark:text-muted-dark">暂无可用投资账户，先创建券商或基金账户。</Text>
            {onOpenAccounts ? <Pressable accessibilityRole="button" accessibilityLabel="创建投资账户" onPress={onOpenAccounts} className="min-h-11 justify-center rounded-lg bg-accent px-3"><Text className="text-center font-bold text-canvas-dark">去创建账户</Text></Pressable> : null}
          </View>
        ) : (
          investmentAccounts.map((account) => (
            <Pressable
              key={account.id}
              accessibilityLabel={`查看投资账户 ${account.name}`}
              accessibilityRole="button"
              onPress={() => onOpenAccount(account.id)}
              testID={`investment-account-${account.id}`}
              className="rounded-xl bg-surface-light p-4 dark:bg-surface-dark"
            >
              <View className="flex-row items-center justify-between">
                <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{account.name}</Text>
                <Text className="text-xs text-muted-light dark:text-muted-dark">查看持仓 ›</Text>
              </View>
              <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
                {ACCOUNT_TYPE_LABELS[account.accountType] ?? account.accountType} · {account.currency}
              </Text>
            </Pressable>
          ))
        )}
      </View>

      <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-product-search">
        <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">搜索金融产品</Text>
        <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">支持股票、基金、ETF 和手工证券；自动行情来自服务端 Tushare，页面不直接访问供应商。</Text>
        <TextInput
          accessibilityLabel="搜索金融产品"
          value={searchQuery}
          onChangeText={setSearchQuery}
          onSubmitEditing={() => void searchProducts()}
          placeholder="输入名称或代码"
          returnKeyType="search"
          testID="investment-product-search-input"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
        />
        <Pressable
          accessibilityLabel={searching ? '正在搜索金融产品' : '搜索金融产品'}
          accessibilityRole="button"
          accessibilityState={{ busy: searching, disabled: searching }}
          disabled={searching}
          onPress={() => void searchProducts()}
          testID="investment-product-search-submit"
          className={`min-h-11 items-center justify-center rounded-lg bg-accent ${searching ? 'opacity-50' : 'active:opacity-70'}`}
        >
          <Text className="font-bold text-canvas-dark">{searching ? '搜索中…' : '搜索'}</Text>
        </Pressable>
        {searchMessage ? <Text accessibilityRole="alert" testID="investment-product-search-message" className="text-sm text-ink-light dark:text-ink-dark">{searchMessage}</Text> : null}
        {searchResults?.map((instrument) => (
          <Pressable
            key={instrument.id}
            accessibilityLabel={`选择产品 ${instrument.name}`}
            accessibilityRole="button"
            accessibilityState={{ selected: selectedInstrument?.id === instrument.id }}
            onPress={() => selectInstrument(instrument)}
            testID={`investment-product-${instrument.id}`}
            className={`rounded-lg border p-3 ${selectedInstrument?.id === instrument.id ? 'border-accent bg-accent/10' : 'border-accent/30'}`}
          >
            <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{instrument.name}</Text>
            <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
              {INSTRUMENT_TYPE_LABELS[instrument.instrumentType]} · {instrument.market} · {instrument.currency} · {instrument.status}
            </Text>
            <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
              数据映射：{instrument.sourceMappings.map((mapping) => `${mapping.source} ${mapping.externalCode}`).join('、') || '暂无'}
            </Text>
          </Pressable>
        ))}
        <Pressable
          accessibilityLabel={manualFormOpen ? '收起创建手工产品' : '创建手工产品'}
          accessibilityRole="button"
          accessibilityState={{ expanded: manualFormOpen }}
          onPress={() => {
            setManualFormOpen((value) => !value);
            setManualMessage(null);
          }}
          testID="investment-manual-product-toggle"
          className="min-h-11 items-center justify-center rounded-lg border border-accent px-3"
        >
          <Text className="font-semibold text-ink-light dark:text-ink-dark">{manualFormOpen ? '收起手工产品表单' : '没有结果？创建手工产品'}</Text>
        </Pressable>
        {manualFormOpen ? (
          <View className="gap-2 rounded-lg border border-accent/30 p-3" testID="investment-manual-product-form">
            <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">产品名称</Text>
            <TextInput
              accessibilityLabel="手工产品名称"
              value={manualName}
              onChangeText={(value) => {
                manualIdempotencyKey.current = null;
                setManualName(value);
              }}
              placeholder="例如：某私募基金"
              testID="investment-manual-product-name"
              className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
            />
            <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">产品类型</Text>
            <View className="flex-row flex-wrap gap-2">
              {(Object.keys(INSTRUMENT_TYPE_LABELS) as Instrument['instrumentType'][]).map((type) => (
                <Pressable
                  key={type}
                  accessibilityLabel={`手工产品类型 ${INSTRUMENT_TYPE_LABELS[type]}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: manualType === type }}
                  onPress={() => {
                    manualIdempotencyKey.current = null;
                    setManualType(type);
                  }}
                  testID={`investment-manual-product-type-${type}`}
                  className={`min-h-9 justify-center rounded-lg border px-3 ${manualType === type ? 'border-accent bg-accent' : 'border-accent/40'}`}
                >
                  <Text className={manualType === type ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{INSTRUMENT_TYPE_LABELS[type]}</Text>
                </Pressable>
              ))}
            </View>
            <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">币种</Text>
            <View className="flex-row flex-wrap gap-2">
              {CURRENCIES.map((currency) => (
                <Pressable
                  key={currency}
                  accessibilityLabel={`手工产品币种 ${currency}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: manualCurrency === currency }}
                  onPress={() => {
                    manualIdempotencyKey.current = null;
                    setManualCurrency(currency);
                  }}
                  testID={`investment-manual-product-currency-${currency}`}
                  className={`min-h-9 justify-center rounded-lg border px-3 ${manualCurrency === currency ? 'border-accent bg-accent' : 'border-accent/40'}`}
                >
                  <Text className={manualCurrency === currency ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{currency}</Text>
                </Pressable>
              ))}
            </View>
            {manualMessage ? <Text accessibilityRole="alert" testID="investment-manual-product-message" className="text-sm text-destructive">{manualMessage}</Text> : null}
            <Pressable
              accessibilityLabel={manualSubmitting ? '正在创建手工产品' : '保存手工产品'}
              accessibilityRole="button"
              accessibilityState={{ busy: manualSubmitting, disabled: manualSubmitting }}
              disabled={manualSubmitting}
              onPress={() => void createManualInstrument()}
              testID="investment-manual-product-submit"
              className={`min-h-11 items-center justify-center rounded-lg bg-accent ${manualSubmitting ? 'opacity-50' : ''}`}
            >
              <Text className="font-bold text-canvas-dark">{manualSubmitting ? '保存中…' : '保存手工产品'}</Text>
            </Pressable>
          </View>
        ) : null}
      </View>

      <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-trade-form">
        <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">录入投资交易</Text>
        <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">买入、卖出和分红只使用投资账户的 PRIMARY 券商现金；成交金额和收益由服务端记账、重建和计算。</Text>
        <View className="flex-row gap-2">
          {(Object.keys(TRADE_SIDE_LABELS) as TradeSide[]).map((side) => (
            <Pressable
              key={side}
              accessibilityLabel={`交易类型 ${TRADE_SIDE_LABELS[side]}`}
              accessibilityRole="button"
              accessibilityState={{ selected: tradeSide === side }}
              onPress={() => {
                tradeIdempotencyKey.current = null;
                setTradeSide(side);
                setTradeMessage(null);
              }}
              testID={`investment-trade-side-${side}`}
              className={`min-h-11 flex-1 items-center justify-center rounded-lg border ${tradeSide === side ? 'border-accent bg-accent' : 'border-accent/40'}`}
            >
              <Text className={tradeSide === side ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{TRADE_SIDE_LABELS[side]}</Text>
            </Pressable>
          ))}
        </View>

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">投资账户</Text>
        {investmentAccounts.length > 0 ? (
          <View className="gap-2">
            {investmentAccounts.map((account) => (
              <Pressable
                key={account.id}
                accessibilityLabel={`选择投资账户 ${account.name}`}
                accessibilityRole="button"
                accessibilityState={{ selected: tradeAccountId === account.id }}
                onPress={() => {
                  tradeIdempotencyKey.current = null;
                  setTradeAccountId(account.id);
                }}
                testID={`investment-trade-account-${account.id}`}
                className={`min-h-11 justify-center rounded-lg border px-3 ${tradeAccountId === account.id ? 'border-accent bg-accent/10' : 'border-accent/40'}`}
              >
                <Text className="text-sm text-ink-light dark:text-ink-dark">{account.name} · {account.currency}</Text>
              </Pressable>
            ))}
          </View>
        ) : <Text className="text-sm text-muted-light dark:text-muted-dark">暂无可用投资账户。</Text>}

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">交易产品</Text>
        {selectedInstrument ? (
          <View className="rounded-lg border border-accent/30 p-3" testID="investment-trade-selected-product">
            <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{selectedInstrument.name}</Text>
            <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">{INSTRUMENT_TYPE_LABELS[selectedInstrument.instrumentType]} · {selectedInstrument.currency} · {selectedInstrument.market}</Text>
          </View>
        ) : <Text className="text-sm text-muted-light dark:text-muted-dark">请先在上方搜索并选择产品。</Text>}

        {tradeSide === 'DIVIDEND' ? (
          <TradeField accessibilityLabel="分红金额" label={`分红金额（${tradeCurrency}）`} value={dividendAmount} onChangeText={(value) => { tradeIdempotencyKey.current = null; setDividendAmount(value); }} placeholder="0.00" testID="investment-trade-dividend" />
        ) : (
          <>
            <TradeField accessibilityLabel="交易数量" label="数量" value={quantity} onChangeText={(value) => { tradeIdempotencyKey.current = null; setQuantity(value); }} placeholder="支持基金小数份额" testID="investment-trade-quantity" />
            <TradeField accessibilityLabel="成交价格" label={`成交价格（${tradeCurrency}）`} value={unitPrice} onChangeText={(value) => { tradeIdempotencyKey.current = null; setUnitPrice(value); }} placeholder="0.00" testID="investment-trade-unit-price" />
          </>
        )}
        <TradeField accessibilityLabel="手续费" label={`手续费（${tradeCurrency}）`} value={feeAmount} onChangeText={(value) => { tradeIdempotencyKey.current = null; setFeeAmount(value); }} placeholder="没有费用请填 0" testID="investment-trade-fee" />
        <TradeField accessibilityLabel="税费" label={`税费（${tradeCurrency}）`} value={taxAmount} onChangeText={(value) => { tradeIdempotencyKey.current = null; setTaxAmount(value); }} placeholder="没有税费请填 0" testID="investment-trade-tax" />
        <TradeField accessibilityLabel="交易时间" label="交易时间（ISO 8601）" value={tradeAt} onChangeText={(value) => { tradeIdempotencyKey.current = null; setTradeAt(value); }} placeholder="2026-09-03T12:00:00+08:00" testID="investment-trade-at" />
        {tradeMessage ? <Text accessibilityRole="alert" testID="investment-trade-message" className="text-sm text-ink-light dark:text-ink-dark">{tradeMessage}</Text> : null}
        <Pressable
          accessibilityLabel={tradeSubmitting ? '正在保存投资交易' : `保存${TRADE_SIDE_LABELS[tradeSide]}交易`}
          accessibilityRole="button"
          accessibilityState={{ busy: tradeSubmitting, disabled: tradeSubmitting }}
          disabled={tradeSubmitting}
          onPress={() => void submitTrade()}
          testID="investment-trade-submit"
          className={`min-h-11 items-center justify-center rounded-lg bg-accent ${tradeSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
        >
          <Text className="font-bold text-canvas-dark">{tradeSubmitting ? '保存中…' : `保存${TRADE_SIDE_LABELS[tradeSide]}交易`}</Text>
        </Pressable>
        <Text className="text-xs leading-5 text-muted-light dark:text-muted-dark">同一载荷失败重试会复用幂等键；服务端成功后才更新账务、持仓和统计。</Text>
      </View>

      <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
        <InvestmentReturnCalendar api={api} selectedInstrument={selectedInstrument} initialMonth={initialMonth} />
      </View>
    </ScrollView>
  );
}

function InvestmentOverviewCard({ overview }: { overview: InvestmentOverview }) {
  return (
    <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark" testID="investment-overview">
      <Text className="text-xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">投资概览</Text>
      <View className="flex-row flex-wrap gap-2">
        <OverviewMetric label="券商现金" value={`${overview.brokerCash} ${overview.baseCurrency}`} />
        <OverviewMetric label="持仓市值" value={`${overview.positionMarketValue} ${overview.baseCurrency}`} />
        <OverviewMetric label="投资资产" value={`${overview.totalInvestmentAssets} ${overview.baseCurrency}`} />
      </View>
      {overview.unpricedInstrumentCount > 0 ? (
        <Text accessibilityRole="alert" testID="investment-overview-unpriced" className="text-sm text-amber-700 dark:text-amber-300">
          有 {overview.unpricedInstrumentCount} 个标的缺少有效价格，未估值字段保持为空，请打开持仓详情处理。
        </Text>
      ) : (
        <Text className="text-sm text-muted-light dark:text-muted-dark">当前没有服务端标记的未估值标的。</Text>
      )}
    </View>
  );
}

function OverviewMetric({ label, value }: { label: string; value: string }) {
  return (
    <View className="min-w-[45%] flex-1 rounded-lg bg-canvas-light p-3 dark:bg-canvas-dark">
      <Text className="text-xs text-muted-light dark:text-muted-dark">{label}</Text>
      <Text className="mt-1 text-base font-bold text-ink-light dark:text-ink-dark">{value}</Text>
    </View>
  );
}

function MarketQualityCard({ status }: { status: MarketDataStatusEnvelope['data'] | null }) {
  return (
    <View className="gap-2 rounded-xl border border-accent/30 p-4" testID="investment-market-quality">
      <Text className="text-base font-bold text-ink-light dark:text-ink-dark">行情质量</Text>
      {status ? (
        <>
          <Text className="text-sm text-ink-light dark:text-ink-dark">状态：{MARKET_STATUS_LABELS[status.status]} · 新鲜度：{FRESHNESS_LABELS[status.freshness]}</Text>
          <Text className="text-sm text-ink-light dark:text-ink-dark">来源：Tushare Pro（盘后行情 / 最新公布净值）</Text>
          <Text className="text-xs text-muted-light dark:text-muted-dark">最近成功同步：{status.lastSuccessfulSyncAt ?? '暂无成功同步'}</Text>
          {status.status !== 'AVAILABLE' || status.freshness !== 'FRESH' ? (
            <Text accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">行情可能不可用或已过期；页面不会将缺失价格当作 0。</Text>
          ) : null}
        </>
      ) : <Text accessibilityRole="alert" className="text-sm text-amber-700 dark:text-amber-300">行情状态暂不可用；可用手工产品和手工价格继续记录，缺失估值不会被伪造。</Text>}
    </View>
  );
}

function TradeField({
  accessibilityLabel,
  label,
  value,
  onChangeText,
  placeholder,
  testID,
}: {
  accessibilityLabel: string;
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  placeholder: string;
  testID: string;
}) {
  return (
    <View className="gap-1">
      <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{label}</Text>
      <TextInput
        accessibilityLabel={accessibilityLabel}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        testID={testID}
        keyboardType={accessibilityLabel === '交易时间' ? 'default' : 'decimal-pad'}
        className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark"
      />
    </View>
  );
}
