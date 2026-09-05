import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircleIcon,
  CalendarDaysIcon,
  CalendarOffIcon,
  CheckCircle2Icon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  CircleSlash2Icon,
  Clock3Icon,
  InfoIcon,
  LoaderCircleIcon,
  PlusIcon,
  SearchIcon,
  TriangleAlertIcon,
} from 'lucide-react'
import { useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'

import type { components } from '@ziji/api-types'

import { useWebAuth } from '@/auth/auth-session'
import { listAccounts } from '@/accounts/accounts-api'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { describeProblem } from '@/lib/problem-messages'
import {
  createInstrument,
  createInvestmentTrade,
  createManualPrice,
  fetchInstrument,
  fetchInvestmentOverview,
  fetchInvestmentPerformance,
  fetchInvestmentReturnCalendar,
  fetchInvestmentReturnDayDetails,
  fetchMarketDataStatus,
  listInstrumentPrices,
  listInvestmentPositions,
  searchInstruments,
  type CreateInstrumentBody,
  type CreateInvestmentTradeBody,
  type CreatePriceBody,
  type Instrument,
  type InvestmentReturnContribution,
  type Position,
  type PriceSnapshot,
  type TradeSide,
} from '@/investments/investments-api'

type Currency = components['schemas']['Currency']
type DataQualityWarning = components['schemas']['DataQualityWarning']
type InvestmentReturnDayStatus = components['schemas']['InvestmentReturnDay']['status']

const CURRENCIES = ['CNY', 'USD', 'HKD', 'JPY', 'EUR'] as const
const INSTRUMENT_TYPES: { value: CreateInstrumentBody['instrumentType']; label: string }[] = [
  { value: 'STOCK', label: '股票' },
  { value: 'FUND', label: '基金' },
  { value: 'ETF', label: 'ETF' },
  { value: 'OTHER', label: '其他证券' },
]
const PRICE_TYPES: { value: CreatePriceBody['priceType']; label: string }[] = [
  { value: 'CLOSE', label: '手工收盘价' },
  { value: 'UNIT_NAV', label: '手工单位净值' },
  { value: 'MANUAL', label: '手工价格' },
]
const TRADE_SIDES: { value: TradeSide; label: string }[] = [
  { value: 'BUY', label: '买入' },
  { value: 'SELL', label: '卖出' },
  { value: 'DIVIDEND', label: '分红' },
]

const RETURN_STATUS_LABELS: Record<InvestmentReturnDayStatus, string> = {
  CALCULATED: '已计算',
  NON_TRADING_DAY: '非交易日',
  NO_POSITION: '当日无持仓',
  PENDING_DATA: '待数据',
  PARTIAL: '部分估值',
  UNPRICED: '无法估值',
}

const RETURN_STATUS_DESCRIPTIONS: Record<InvestmentReturnDayStatus, string> = {
  CALCULATED: '数据完整，收益可以为正、负或真实的零。',
  NON_TRADING_DAY: '相关市场休市，不显示为零收益。',
  NO_POSITION: '该日统计范围内没有持仓。',
  PENDING_DATA: '行情、净值或投影尚未完成。',
  PARTIAL: '部分标的缺少价格或汇率，完整收益不可用。',
  UNPRICED: '统计范围没有可用估值，收益保持为空。',
}

const WARNING_LABELS: Record<DataQualityWarning['code'], string> = {
  UNPRICED_INSTRUMENTS: '持仓缺少有效价格',
  MISSING_EXCHANGE_RATES: '缺少汇率',
  STALE_MARKET_DATA: '行情可能已过期',
}

const PRICE_SOURCE_LABELS: Record<PriceSnapshot['source'], string> = {
  THS: '同花顺（盘后）',
  MANUAL: '手工价格',
}

const PRICE_FRESHNESS_LABELS: Record<PriceSnapshot['freshness'], string> = {
  FRESH: '新鲜',
  STALE: '已过期',
  UNAVAILABLE: '不可用',
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function nowLocalInput(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60000
  return new Date(now.getTime() - offset).toISOString().slice(0, 16)
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7)
}

function shiftMonth(month: string, offset: number): string {
  const [year, monthNumber] = month.split('-').map(Number)
  const shifted = new Date(Date.UTC(year, monthNumber - 1 + offset, 1))
  return shifted.toISOString().slice(0, 7)
}

function monthLabel(month: string): string {
  const [year, monthNumber] = month.split('-')
  return `${year} 年 ${Number(monthNumber)} 月`
}

function toInstant(localValue: string): string {
  // 服务端根据用户时区固化 businessDate；浏览器只负责把 datetime-local 转为 ISO 时间。
  return new Date(localValue).toISOString()
}

function isZeroDecimal(value: string | null | undefined): boolean {
  return value !== null && value !== undefined && /^[-+]?0+(?:\.0+)?$/.test(value.trim())
}

function isPositiveDecimal(value: string): boolean {
  const trimmed = value.trim()
  return /^\d+(?:\.\d+)?$/.test(trimmed) && !isZeroDecimal(trimmed)
}

function isNonNegativeDecimal(value: string): boolean {
  const trimmed = value.trim()
  return /^\d+(?:\.\d+)?$/.test(trimmed)
}

function displayMoney(value: string | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—'
  return currency ? `${value} ${currency}` : value
}

function displaySignedMoney(value: string | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—'
  const trimmed = value.trim()
  const shown = isZeroDecimal(trimmed) ? `±${trimmed}` : trimmed.startsWith('-') ? trimmed : `+${trimmed}`
  return currency ? `${shown} ${currency}` : shown
}

function displayPercent(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const trimmed = value.trim()
  const negative = trimmed.startsWith('-')
  const unsigned = trimmed.replace(/^[-+]/, '')
  const [whole, fraction = ''] = unsigned.split('.')
  const digits = `${whole}${fraction}`.padEnd(whole.length + 2, '0')
  const decimalIndex = whole.length + 2
  const integerPart = digits.slice(0, decimalIndex).replace(/^0+(?=\d)/, '') || '0'
  const fractionPart = digits.slice(decimalIndex).replace(/0+$/, '')
  const percent = fractionPart === '' ? `${integerPart}.00` : `${integerPart}.${fractionPart}`
  return `${negative ? '-' : '+'}${percent}%`
}

function displayTimestamp(value: string | null | undefined): string {
  return value ? value.replace('T', ' ') : '—'
}

function instrumentCode(instrument: Instrument | undefined): string | null {
  return instrument?.sourceMappings.find((mapping) => mapping.source === 'THS')?.externalCode
    ?? instrument?.sourceMappings.find((mapping) => mapping.source === 'MANUAL')?.externalCode
    ?? null
}

function mergeInstruments(...groups: (Instrument | undefined)[][]): Instrument[] {
  const byId = new Map<string, Instrument>()
  for (const group of groups) {
    for (const instrument of group) {
      if (instrument) byId.set(instrument.id, instrument)
    }
  }
  return Array.from(byId.values())
}

function statusIcon(status: InvestmentReturnDayStatus) {
  if (status === 'CALCULATED') return CheckCircle2Icon
  if (status === 'NON_TRADING_DAY') return CalendarOffIcon
  if (status === 'NO_POSITION') return CircleSlash2Icon
  if (status === 'PENDING_DATA') return Clock3Icon
  if (status === 'PARTIAL') return CircleAlertIcon
  return TriangleAlertIcon
}

function returnStatusVariant(status: InvestmentReturnDayStatus): 'default' | 'outline' | 'secondary' | 'destructive' {
  if (status === 'UNPRICED') return 'destructive'
  if (status === 'CALCULATED') return 'secondary'
  return 'outline'
}

function QueryError({ title, error, onRetry }: { title: string; error: unknown; onRetry: () => void }) {
  return (
    <Alert variant="destructive">
      <AlertCircleIcon />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription className="flex flex-wrap items-center gap-2">
        <span>{describeProblem(error)}</span>
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>重试</Button>
      </AlertDescription>
    </Alert>
  )
}

function MetricCard({ label, value, description }: { label: string; value: string; description: string }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="font-heading text-xl font-semibold tracking-tight">{value}</p>
        <p className="text-xs text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  )
}

function PositionRow({
  position,
  instrument,
  price,
  priceLoading,
  priceError,
  onManualPrice,
}: {
  position: Position
  instrument?: Instrument
  price?: PriceSnapshot
  priceLoading: boolean
  priceError: boolean
  onManualPrice: (instrumentId: string) => void
}) {
  const unpriced = position.valuationStatus === 'UNPRICED'
  const valueOrUnavailable = (value: string | null | undefined, unavailable = unpriced) => {
    if (unavailable) return '—（UNPRICED）'
    return displayMoney(value, instrument?.currency)
  }
  const PriceIcon = price?.freshness === 'STALE' ? Clock3Icon : price?.freshness === 'UNAVAILABLE' ? TriangleAlertIcon : InfoIcon

  return (
    <li className="flex flex-col gap-4 rounded-lg border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{instrument?.name ?? '产品信息加载中'}</span>
            <Badge variant="outline">{instrument?.instrumentType ?? 'INSTRUMENT'}</Badge>
            {instrumentCode(instrument) ? <Badge variant="outline">{instrumentCode(instrument)}</Badge> : null}
            <Badge variant={unpriced ? 'destructive' : 'secondary'}>
              {unpriced ? 'UNPRICED · 无法估值' : 'PRICED · 已估值'}
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground">
            {instrument ? `${instrument.market} · ${instrument.currency}` : `标的 ID：${position.instrumentId}`}
          </p>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={() => onManualPrice(position.instrumentId)}>
          <PlusIcon data-icon="inline-start" />补录价格
        </Button>
      </div>

      <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">持仓数量</dt><dd>{displayMoney(position.quantity)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">移动平均成本</dt><dd>{displayMoney(position.averageCost, instrument?.currency)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">持仓成本</dt><dd>{displayMoney(position.costBasis, instrument?.currency)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">当前价格</dt><dd>{valueOrUnavailable(position.marketPrice)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">当前市值</dt><dd>{valueOrUnavailable(position.marketValue)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">未实现盈亏</dt><dd>{unpriced ? '—（UNPRICED）' : displaySignedMoney(position.unrealizedProfit, instrument?.currency)}</dd></div>
        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">价格对应日期</dt><dd>{position.priceAsOf ?? '—（暂无有效价格）'}</dd></div>
      </dl>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t pt-3 text-xs text-muted-foreground">
        <span className="flex items-center gap-1"><PriceIcon />行情来源：{price ? PRICE_SOURCE_LABELS[price.source] : priceLoading ? '读取中…' : priceError ? '不可用' : '未返回'}</span>
        <span>行情业务日期：{price?.businessDate ?? position.priceAsOf ?? '—'}</span>
        <span>获取时间：{displayTimestamp(price?.fetchedAt)}</span>
        {price ? <Badge variant={price.freshness === 'FRESH' ? 'secondary' : price.freshness === 'STALE' ? 'outline' : 'destructive'}>{PRICE_FRESHNESS_LABELS[price.freshness]}</Badge> : null}
        {unpriced ? <span>请补录有效价格后再查看估值结果。</span> : null}
      </div>
    </li>
  )
}

function ContributionList({ contributions, currency }: { contributions: InvestmentReturnContribution[]; currency: string }) {
  if (contributions.length === 0) return <p className="text-sm text-muted-foreground">服务端未返回贡献明细。</p>
  return (
    <ul className="flex flex-col gap-2" data-testid="return-contributions">
      {contributions.map((contribution, index) => (
        <li key={`${contribution.instrumentId ?? contribution.contributionType}-${index}`} className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3 text-sm">
          <div className="flex min-w-0 flex-col gap-1">
            <span className="font-medium">{contribution.label}</span>
            <span className="text-xs text-muted-foreground">
              {contribution.contributionType} · 价格日期 {contribution.priceAsOf ?? '—'} · {contribution.status}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <span>{displaySignedMoney(contribution.profit, currency)}</span>
            <span className="text-muted-foreground">{displayPercent(contribution.returnRate)}</span>
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * 表单载荷变化时重新生成幂等键：同一载荷在重渲染间保持稳定，
 * 载荷一旦变化立即换键，避免同键异参触发 409。
 */
function usePayloadKey(payload: string): string {
  const ref = useRef<{ payload: string; key: string } | null>(null)
  if (ref.current === null || ref.current.payload !== payload) {
    ref.current = { payload, key: globalThis.crypto.randomUUID() }
  }
  return ref.current.key
}

export function InvestmentsPage() {
  const { user } = useWebAuth()
  const queryClient = useQueryClient()
  const [instrumentToolsOpen, setInstrumentToolsOpen] = useState(false)
  const [tradeFormOpen, setTradeFormOpen] = useState(false)
  const [instrumentSearchText, setInstrumentSearchText] = useState('')
  const [submittedInstrumentQuery, setSubmittedInstrumentQuery] = useState('')
  const [selectedInstrument, setSelectedInstrument] = useState<Instrument | null>(null)
  const [createInstrumentOpen, setCreateInstrumentOpen] = useState(false)
  const [instrumentType, setInstrumentType] = useState<CreateInstrumentBody['instrumentType']>('STOCK')
  const [instrumentName, setInstrumentName] = useState('')
  const [instrumentMarket, setInstrumentMarket] = useState('MANUAL')
  const [instrumentSourceCode, setInstrumentSourceCode] = useState('')
  const [instrumentCurrency, setInstrumentCurrency] = useState<Currency>(user?.baseCurrency ?? 'CNY')
  const [manualPriceInstrumentId, setManualPriceInstrumentId] = useState<string | null>(null)
  const [manualPriceType, setManualPriceType] = useState<CreatePriceBody['priceType']>('MANUAL')
  const [manualPriceDate, setManualPriceDate] = useState(todayIso)
  const [manualPriceValue, setManualPriceValue] = useState('')
  const [manualPriceReason, setManualPriceReason] = useState('')
  const [tradeSide, setTradeSide] = useState<TradeSide>('BUY')
  const [tradeAccountId, setTradeAccountId] = useState('')
  const [tradeInstrumentId, setTradeInstrumentId] = useState('')
  const [tradeQuantity, setTradeQuantity] = useState('')
  const [tradeUnitPrice, setTradeUnitPrice] = useState('')
  const [tradeDividendAmount, setTradeDividendAmount] = useState('')
  const [tradeFeeAmount, setTradeFeeAmount] = useState('0')
  const [tradeTaxAmount, setTradeTaxAmount] = useState('0')
  const [tradeAt, setTradeAt] = useState(nowLocalInput)
  const [instrumentFormError, setInstrumentFormError] = useState<string | null>(null)
  const [manualPriceFormError, setManualPriceFormError] = useState<string | null>(null)
  const [tradeFormError, setTradeFormError] = useState<string | null>(null)
  const [instrumentCreatedId, setInstrumentCreatedId] = useState<string | null>(null)
  const [manualPriceCreatedId, setManualPriceCreatedId] = useState<string | null>(null)
  const [tradeCreatedId, setTradeCreatedId] = useState<string | null>(null)
  const [calendarMonth, setCalendarMonth] = useState(currentMonth)
  const [calendarScope, setCalendarScope] = useState<components['parameters']['InvestmentReturnScopeType']>('PORTFOLIO')
  const [calendarInstrumentId, setCalendarInstrumentId] = useState('')
  // 收益日历的金额/收益率切换属于 React 界面状态，避免直接改 DOM 后与查询刷新脱节。
  const [calendarDisplayMode, setCalendarDisplayMode] = useState<'AMOUNT' | 'RATE'>('AMOUNT')
  const [selectedReturnDate, setSelectedReturnDate] = useState<string | null>(null)
  const dayButtonRefs = useRef<Record<string, HTMLButtonElement | null>>({})

  const overviewQuery = useQuery({
    queryKey: ['investments', 'overview'],
    queryFn: () => fetchInvestmentOverview(),
    staleTime: 30_000,
  })
  const accountsQuery = useQuery({
    queryKey: ['accounts', 'list'],
    queryFn: listAccounts,
    staleTime: 60_000,
  })
  const marketStatusQuery = useQuery({
    queryKey: ['investments', 'market-data'],
    queryFn: () => fetchMarketDataStatus(),
    staleTime: 30_000,
  })
  const instrumentSearchQuery = useQuery({
    queryKey: ['investments', 'instruments', submittedInstrumentQuery],
    queryFn: () => searchInstruments(submittedInstrumentQuery),
    enabled: submittedInstrumentQuery !== '',
    staleTime: 60_000,
  })

  const investmentAccounts = accountsQuery.data?.accounts.filter((account) =>
    account.accountClass === 'INVESTMENT' && account.status === 'ACTIVE') ?? []
  const positionQueries = useQueries({
    queries: investmentAccounts.map((account) => ({
      queryKey: ['investments', 'positions', account.id],
      queryFn: () => listInvestmentPositions(account.id),
      staleTime: 30_000,
    })),
  })
  const performanceQueries = useQueries({
    queries: investmentAccounts.map((account) => ({
      queryKey: ['investments', 'performance', account.id],
      queryFn: () => fetchInvestmentPerformance(account.id),
      staleTime: 30_000,
    })),
  })

  const positionRows = positionQueries.flatMap((query, accountIndex) =>
    (query.data?.data ?? []).map((position) => ({ account: investmentAccounts[accountIndex], position })),
  )
  const instrumentIds = Array.from(new Set(positionRows.map(({ position }) => position.instrumentId)))
  const instrumentQueries = useQueries({
    queries: instrumentIds.map((instrumentId) => ({
      queryKey: ['investments', 'instrument', instrumentId],
      queryFn: () => fetchInstrument(instrumentId),
      staleTime: 300_000,
    })),
  })
  const priceQueries = useQueries({
    queries: instrumentIds.map((instrumentId) => ({
      queryKey: ['investments', 'prices', instrumentId],
      queryFn: () => listInstrumentPrices(instrumentId, { limit: 1 }),
      staleTime: 30_000,
    })),
  })

  const instruments = mergeInstruments(
    instrumentSearchQuery.data?.data ?? [],
    instrumentQueries.map((query) => query.data?.data),
    selectedInstrument ? [selectedInstrument] : [],
  )
  const instrumentById = new Map(instruments.map((instrument) => [instrument.id, instrument]))
  const selectedTradeInstrument = instrumentById.get(tradeInstrumentId)
    ?? (selectedInstrument?.id === tradeInstrumentId ? selectedInstrument : undefined)
  const selectedManualPriceInstrument = instrumentById.get(manualPriceInstrumentId ?? '')
    ?? (selectedInstrument?.id === manualPriceInstrumentId ? selectedInstrument : undefined)
  const selectedTradeAccount = investmentAccounts.find((account) => account.id === tradeAccountId)
  const tradeCurrency = selectedTradeInstrument?.currency
    ?? selectedTradeAccount?.currency
    ?? user?.baseCurrency
    ?? 'CNY'
  const manualPriceCurrency = selectedManualPriceInstrument?.currency ?? user?.baseCurrency ?? 'CNY'

  const calendarQuery = useQuery({
    queryKey: ['investments', 'return-calendar', calendarMonth, calendarScope, calendarInstrumentId],
    queryFn: () => fetchInvestmentReturnCalendar(
      calendarMonth,
      calendarScope,
      calendarScope === 'INSTRUMENT' ? calendarInstrumentId : undefined,
    ),
    enabled: calendarScope === 'PORTFOLIO' || calendarInstrumentId !== '',
    staleTime: 30_000,
  })
  const returnDetailsQuery = useQuery({
    queryKey: ['investments', 'return-day-details', selectedReturnDate, calendarScope, calendarInstrumentId],
    queryFn: () => fetchInvestmentReturnDayDetails(
      selectedReturnDate!,
      calendarScope,
      calendarScope === 'INSTRUMENT' ? calendarInstrumentId : undefined,
    ),
    enabled: selectedReturnDate !== null,
    staleTime: 30_000,
  })

  const createInstrumentPayload = JSON.stringify({ instrumentType, instrumentName, instrumentMarket, instrumentCurrency, instrumentSourceCode })
  const createInstrumentKey = usePayloadKey(createInstrumentPayload)
  const manualPricePayload = JSON.stringify({ manualPriceInstrumentId, manualPriceType, manualPriceDate, manualPriceValue, manualPriceReason, manualPriceCurrency })
  const manualPriceKey = usePayloadKey(manualPricePayload)
  const tradePayload = JSON.stringify({
    tradeSide,
    tradeAccountId,
    tradeInstrumentId,
    tradeQuantity,
    tradeUnitPrice,
    tradeDividendAmount,
    tradeCurrency,
    tradeFeeAmount,
    tradeTaxAmount,
    tradeAt,
  })
  const tradeKey = usePayloadKey(tradePayload)

  const createInstrumentMutation = useMutation({
    mutationFn: () => createInstrument(createInstrumentKey, {
      instrumentType,
      name: instrumentName.trim(),
      market: instrumentMarket.trim() || 'MANUAL',
      currency: instrumentCurrency,
      sourceCode: instrumentSourceCode.trim() || undefined,
    }),
    onSuccess: (response) => {
      setSelectedInstrument(response.data)
      setTradeInstrumentId(response.data.id)
      setCalendarInstrumentId((current) => current || response.data.id)
      setInstrumentCreatedId(response.data.id)
      setInstrumentName('')
      setInstrumentSourceCode('')
      setInstrumentFormError(null)
      void queryClient.invalidateQueries({ queryKey: ['investments'] })
    },
    onError: (error) => setInstrumentFormError(describeProblem(error)),
  })

  const manualPriceMutation = useMutation({
    mutationFn: () => createManualPrice(manualPriceInstrumentId!, manualPriceKey, {
      priceType: manualPriceType,
      businessDate: manualPriceDate,
      price: manualPriceValue.trim(),
      currency: manualPriceCurrency,
      reason: manualPriceReason.trim(),
    }),
    onSuccess: (response) => {
      setManualPriceCreatedId(response.data.id)
      setManualPriceFormError(null)
      void queryClient.invalidateQueries({ queryKey: ['investments'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => setManualPriceFormError(describeProblem(error)),
  })

  const tradeMutation = useMutation({
    mutationFn: () => {
      const body: CreateInvestmentTradeBody = {
        side: tradeSide,
        investmentAccountId: tradeAccountId,
        instrumentId: tradeInstrumentId,
        currency: tradeCurrency,
        feeAmount: tradeFeeAmount.trim() || '0',
        taxAmount: tradeTaxAmount.trim() || '0',
        tradeAt: toInstant(tradeAt),
      }
      if (tradeSide === 'DIVIDEND') body.dividendAmount = tradeDividendAmount.trim()
      else {
        body.quantity = tradeQuantity.trim()
        body.unitPrice = tradeUnitPrice.trim()
      }
      return createInvestmentTrade(tradeKey, body)
    },
    onSuccess: (response) => {
      setTradeCreatedId(response.data.id)
      setTradeFormError(null)
      void queryClient.invalidateQueries({ queryKey: ['investments'] })
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => setTradeFormError(describeProblem(error)),
  })

  function selectInstrument(instrument: Instrument, useFor: 'trade' | 'price' | 'calendar' = 'trade') {
    setSelectedInstrument(instrument)
    if (useFor === 'trade') setTradeInstrumentId(instrument.id)
    if (useFor === 'price') {
      setManualPriceInstrumentId(instrument.id)
      setManualPriceType(instrument.instrumentType === 'FUND' ? 'UNIT_NAV' : 'MANUAL')
      setManualPriceCreatedId(null)
    }
    if (useFor === 'calendar') setCalendarInstrumentId(instrument.id)
  }

  function openManualPrice(instrumentId: string) {
    setManualPriceInstrumentId(instrumentId)
    setManualPriceType(instrumentById.get(instrumentId)?.instrumentType === 'FUND' ? 'UNIT_NAV' : 'MANUAL')
    setManualPriceCreatedId(null)
    setManualPriceFormError(null)
    setInstrumentToolsOpen(true)
  }

  function submitInstrumentSearch(event: FormEvent) {
    event.preventDefault()
    const query = instrumentSearchText.trim()
    if (query === '') return
    setSubmittedInstrumentQuery(query)
  }

  function submitCreateInstrument(event: FormEvent) {
    event.preventDefault()
    if (instrumentName.trim() === '') {
      setInstrumentFormError('产品名称不能为空')
      return
    }
    setInstrumentFormError(null)
    setInstrumentCreatedId(null)
    createInstrumentMutation.mutate()
  }

  function submitManualPrice(event: FormEvent) {
    event.preventDefault()
    if (!manualPriceInstrumentId || !selectedManualPriceInstrument) {
      setManualPriceFormError('正在读取产品信息，请稍后重试')
      return
    }
    if (!isPositiveDecimal(manualPriceValue)) {
      setManualPriceFormError('价格必须是大于 0 的十进制字符串')
      return
    }
    if (manualPriceDate === '') {
      setManualPriceFormError('请选择价格对应日期')
      return
    }
    if (manualPriceReason.trim() === '') {
      setManualPriceFormError('请填写手工价格原因')
      return
    }
    setManualPriceFormError(null)
    setManualPriceCreatedId(null)
    manualPriceMutation.mutate()
  }

  function submitTrade(event: FormEvent) {
    event.preventDefault()
    if (!tradeAccountId) {
      setTradeFormError('请选择投资账户')
      return
    }
    if (!UUID_PATTERN.test(tradeInstrumentId) || !selectedTradeInstrument) {
      setTradeFormError('请先从产品搜索结果中选择标的')
      return
    }
    if (tradeSide === 'DIVIDEND' ? !isPositiveDecimal(tradeDividendAmount) : !isPositiveDecimal(tradeQuantity) || !isPositiveDecimal(tradeUnitPrice)) {
      setTradeFormError(tradeSide === 'DIVIDEND' ? '分红金额必须是大于 0 的十进制字符串' : '数量和成交单价必须是大于 0 的十进制字符串')
      return
    }
    if (!isNonNegativeDecimal(tradeFeeAmount) || !isNonNegativeDecimal(tradeTaxAmount)) {
      setTradeFormError('手续费和税费必须是非负十进制字符串')
      return
    }
    if (tradeAt === '') {
      setTradeFormError('请选择成交时间')
      return
    }
    setTradeFormError(null)
    setTradeCreatedId(null)
    tradeMutation.mutate()
  }

  function changeCalendarScope(nextScope: components['parameters']['InvestmentReturnScopeType']) {
    setCalendarScope(nextScope)
    setSelectedReturnDate(null)
  }

  function changeCalendarInstrument(nextInstrumentId: string) {
    setCalendarInstrumentId(nextInstrumentId)
    setSelectedReturnDate(null)
  }

  function changeCalendarMonth(nextMonth: string) {
    setCalendarMonth(nextMonth)
    setSelectedReturnDate(null)
  }

  function handleCalendarKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const days = calendarQuery.data?.data.days ?? []
    const targetIndex = event.key === 'ArrowRight' ? index + 1
      : event.key === 'ArrowLeft' ? index - 1
        : event.key === 'ArrowDown' ? index + 7
          : event.key === 'ArrowUp' ? index - 7
            : event.key === 'Home' ? 0
              : event.key === 'End' ? days.length - 1
                : -1
    if (targetIndex < 0 || targetIndex >= days.length) return
    event.preventDefault()
    dayButtonRefs.current[days[targetIndex].businessDate]?.focus()
  }

  const latestPriceByInstrumentId = new Map(instrumentIds.map((instrumentId, index) => [
    instrumentId,
    priceQueries[index]?.data?.data[0],
  ]))

  return (
    <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <Badge variant="outline">B3 · 投资与行情</Badge>
          <h1 className="font-heading text-2xl font-semibold tracking-tight">投资</h1>
          <p className="max-w-2xl text-sm text-muted-foreground">持仓、交易和收益日历均来自服务端事实与投影；行情为盘后数据或最新公布净值，不代表盘中实时行情。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" onClick={() => setInstrumentToolsOpen((open) => !open)}>
            <SearchIcon data-icon="inline-start" />产品与价格
          </Button>
          <Button type="button" onClick={() => setTradeFormOpen((open) => !open)}>
            <PlusIcon data-icon="inline-start" />记录投资交易
          </Button>
        </div>
      </section>

      {marketStatusQuery.isPending ? <Skeleton className="h-16 w-full" /> : marketStatusQuery.isError ? (
        <QueryError title="无法读取行情状态" error={marketStatusQuery.error} onRetry={() => void marketStatusQuery.refetch()} />
      ) : marketStatusQuery.data ? (
        <Alert>
          <InfoIcon />
          <AlertTitle className="flex flex-wrap items-center gap-2">
            行情来源：{marketStatusQuery.data.data.source === 'THS' ? '同花顺（盘后）' : marketStatusQuery.data.data.source}
            <Badge variant={marketStatusQuery.data.data.status === 'AVAILABLE' ? 'secondary' : marketStatusQuery.data.data.status === 'DEGRADED' ? 'outline' : 'destructive'}>{marketStatusQuery.data.data.status}</Badge>
            <Badge variant={marketStatusQuery.data.data.freshness === 'FRESH' ? 'secondary' : marketStatusQuery.data.data.freshness === 'STALE' ? 'outline' : 'destructive'}>{PRICE_FRESHNESS_LABELS[marketStatusQuery.data.data.freshness]}</Badge>
          </AlertTitle>
          <AlertDescription>最近一次成功同步：{displayTimestamp(marketStatusQuery.data.data.lastSuccessfulSyncAt)}。股票/ETF 显示盘后收盘价，基金显示最新公布净值；不可用时请使用手工价格。</AlertDescription>
        </Alert>
      ) : null}

      <section className="flex flex-col gap-4" aria-label="投资核心指标">
        {overviewQuery.isPending ? (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">{[1, 2, 3, 4].map((key) => <Skeleton key={key} className="h-28 w-full" />)}</div>
        ) : overviewQuery.isError ? (
          <QueryError title="无法加载投资概览" error={overviewQuery.error} onRetry={() => void overviewQuery.refetch()} />
        ) : overviewQuery.data ? (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="投资资产" value={displayMoney(overviewQuery.data.data.totalInvestmentAssets, overviewQuery.data.data.baseCurrency)} description="服务端当前估值" />
            <MetricCard label="持仓市值" value={displayMoney(overviewQuery.data.data.positionMarketValue, overviewQuery.data.data.baseCurrency)} description="不含 POSITION_COST 重复计入" />
            <MetricCard label="券商现金" value={displayMoney(overviewQuery.data.data.brokerCash, overviewQuery.data.data.baseCurrency)} description="投资账户 PRIMARY" />
            <MetricCard label="未估值产品" value={`${overviewQuery.data.data.unpricedInstrumentCount} 项`} description="UNPRICED 不按 0 处理" />
          </div>
        ) : null}
      </section>

      {instrumentToolsOpen ? (
        <section aria-label="产品与价格工具">
          <Card>
            <CardHeader>
              <CardTitle>产品搜索与手工价格</CardTitle>
              <CardDescription>搜索服务端已缓存的股票、基金和 ETF；没有外部数据时可创建手工产品并维护价格。</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-5">
              <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitInstrumentSearch}>
                <label className="sr-only" htmlFor="instrument-search">搜索股票、基金或 ETF</label>
                <Input id="instrument-search" value={instrumentSearchText} onChange={(event) => setInstrumentSearchText(event.target.value)} placeholder="名称或代码，例如 510300" />
                <Button type="submit" disabled={instrumentSearchText.trim() === '' || instrumentSearchQuery.isFetching}>
                  {instrumentSearchQuery.isFetching ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : <SearchIcon data-icon="inline-start" />}
                  搜索产品
                </Button>
              </form>

              {instrumentSearchQuery.isError ? <QueryError title="无法搜索产品" error={instrumentSearchQuery.error} onRetry={() => void instrumentSearchQuery.refetch()} /> : null}
              {instrumentSearchQuery.isPending && submittedInstrumentQuery !== '' ? <p className="text-sm text-muted-foreground" aria-busy="true">正在搜索…</p> : null}
              {instrumentSearchQuery.data && instrumentSearchQuery.data.data.length === 0 ? <p className="text-sm text-muted-foreground">没有找到匹配产品，可以创建手工产品。</p> : null}
              {instrumentSearchQuery.data && instrumentSearchQuery.data.data.length > 0 ? (
                <ul className="flex flex-col gap-2" data-testid="instrument-search-results">
                  {instrumentSearchQuery.data.data.map((instrument) => (
                    <li key={instrument.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3">
                      <div className="flex min-w-0 flex-col gap-1">
                        <span className="font-medium">{instrument.name}</span>
                        <span className="text-xs text-muted-foreground">{instrumentCode(instrument) ?? '无外部代码'} · {instrument.instrumentType} · {instrument.market} · {instrument.currency}</span>
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" size="sm" variant="outline" onClick={() => selectInstrument(instrument, 'trade')}>用于交易</Button>
                        <Button type="button" size="sm" variant="outline" onClick={() => selectInstrument(instrument, 'price')}>补录价格</Button>
                        <Button type="button" size="sm" variant="ghost" onClick={() => selectInstrument(instrument, 'calendar')}>查看收益日历</Button>
                      </div>
                    </li>
                  ))}
                </ul>
              ) : null}

              <div className="flex items-center justify-between gap-3 border-t pt-4">
                <div className="flex flex-col gap-1">
                  <span className="font-medium">没有匹配结果？</span>
                  <span className="text-xs text-muted-foreground">手工产品仍需通过服务端创建，之后可用于真实交易。</span>
                </div>
                <Button type="button" variant="outline" onClick={() => setCreateInstrumentOpen((open) => !open)}>手工创建产品</Button>
              </div>

              {createInstrumentOpen ? (
                <form className="grid gap-4 rounded-md border p-4 md:grid-cols-2" onSubmit={submitCreateInstrument} noValidate>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="instrument-type">产品类型</label>
                    <select id="instrument-type" value={instrumentType} onChange={(event) => setInstrumentType(event.target.value as CreateInstrumentBody['instrumentType'])} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                      {INSTRUMENT_TYPES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                    </select>
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="instrument-currency">币种</label>
                    <select id="instrument-currency" value={instrumentCurrency} onChange={(event) => setInstrumentCurrency(event.target.value as Currency)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                      {CURRENCIES.map((currency) => <option key={currency} value={currency}>{currency}</option>)}
                    </select>
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="instrument-name">产品名称</label>
                    <Input id="instrument-name" value={instrumentName} onChange={(event) => setInstrumentName(event.target.value)} aria-invalid={instrumentFormError ? true : undefined} placeholder="例如：我的海外基金" />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="instrument-market">市场或来源标记</label>
                    <Input id="instrument-market" value={instrumentMarket} onChange={(event) => setInstrumentMarket(event.target.value)} placeholder="MANUAL" />
                  </div>
                  <div className="flex flex-col gap-2 md:col-span-2">
                    <label htmlFor="instrument-code">同花顺代码（可选）</label>
                    <Input id="instrument-code" value={instrumentSourceCode} onChange={(event) => setInstrumentSourceCode(event.target.value)} placeholder="6 位代码，如 000001；留空保持纯手工产品" />
                    <p className="text-xs text-muted-foreground">填写后由服务端按该代码拉取盘后日线或最新公布净值；不填写则手工维护价格。</p>
                  </div>
                  {instrumentFormError ? <p className="text-sm text-destructive md:col-span-2" role="alert">{instrumentFormError}</p> : null}
                  {instrumentCreatedId ? <p className="text-sm text-muted-foreground md:col-span-2" role="status">产品已创建：{instrumentCreatedId}</p> : null}
                  <div className="flex items-center gap-3 md:col-span-2">
                    <Button type="submit" disabled={createInstrumentMutation.isPending}>
                      {createInstrumentMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                      创建手工产品
                    </Button>
                    <p className="text-xs text-muted-foreground">提交携带幂等键，服务端负责生成内部 instrument_id。</p>
                  </div>
                </form>
              ) : null}

              {manualPriceInstrumentId ? (
                <form className="flex flex-col gap-4 rounded-md border p-4" onSubmit={submitManualPrice} noValidate>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex flex-col gap-1">
                      <h3 className="font-heading font-medium">补录价格或净值</h3>
                      <p className="text-xs text-muted-foreground">产品：{selectedManualPriceInstrument?.name ?? '产品信息加载中'} · 来源将记录为 MANUAL。</p>
                    </div>
                    <Button type="button" variant="ghost" size="sm" onClick={() => setManualPriceInstrumentId(null)}>取消</Button>
                  </div>
                  <div className="grid gap-4 md:grid-cols-3">
                    <div className="flex flex-col gap-2">
                      <label htmlFor="manual-price-type">价格类型</label>
                      <select id="manual-price-type" value={manualPriceType} onChange={(event) => setManualPriceType(event.target.value as CreatePriceBody['priceType'])} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                        {PRICE_TYPES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                      </select>
                    </div>
                    <div className="flex flex-col gap-2">
                      <label htmlFor="manual-price-date">价格对应日期</label>
                      <Input id="manual-price-date" type="date" value={manualPriceDate} onChange={(event) => setManualPriceDate(event.target.value)} />
                    </div>
                    <div className="flex flex-col gap-2">
                      <label htmlFor="manual-price-value">价格 / 净值（{manualPriceCurrency}）</label>
                      <Input id="manual-price-value" inputMode="decimal" value={manualPriceValue} onChange={(event) => setManualPriceValue(event.target.value)} placeholder="0.0000" />
                    </div>
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="manual-price-reason">手工原因</label>
                    <Input id="manual-price-reason" value={manualPriceReason} onChange={(event) => setManualPriceReason(event.target.value)} placeholder="例如：外部行情暂不可用，依据基金公告补录" />
                  </div>
                  {manualPriceFormError ? <p className="text-sm text-destructive" role="alert">{manualPriceFormError}</p> : null}
                  {manualPriceCreatedId ? <p className="text-sm text-muted-foreground" role="status">价格已保存：{manualPriceCreatedId}</p> : null}
                  <div className="flex items-center gap-3">
                    <Button type="submit" disabled={manualPriceMutation.isPending || !selectedManualPriceInstrument}>
                      {manualPriceMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                      保存手工价格
                    </Button>
                    <span className="text-xs text-muted-foreground">旧价格由服务端保留并按业务日期形成修订记录。</span>
                  </div>
                </form>
              ) : null}
            </CardContent>
          </Card>
        </section>
      ) : null}

      {tradeFormOpen ? (
        <section aria-label="投资交易表单">
          <Card>
            <CardHeader>
              <CardTitle>记录投资交易</CardTitle>
              <CardDescription>买入、卖出和分红只改变投资账户 PRIMARY 券商现金；持仓、费用和收益由服务端原子入账。</CardDescription>
            </CardHeader>
            <CardContent>
              {investmentAccounts.length === 0 && !accountsQuery.isPending ? (
                <Empty>
                  <EmptyHeader>
                    <EmptyMedia variant="icon"><InfoIcon /></EmptyMedia>
                    <EmptyTitle>还没有可用投资账户</EmptyTitle>
                    <EmptyDescription>先创建一个投资账户，投资成交才有服务端结算现金。</EmptyDescription>
                  </EmptyHeader>
                  <EmptyContent><Button asChild><Link to="/accounts/new">创建投资账户</Link></Button></EmptyContent>
                </Empty>
              ) : (
                <form className="grid gap-4 md:grid-cols-2" onSubmit={submitTrade} noValidate>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="trade-side">交易类型</label>
                    <select id="trade-side" value={tradeSide} onChange={(event) => { setTradeSide(event.target.value as TradeSide); setTradeCreatedId(null) }} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                      {TRADE_SIDES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                    </select>
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="trade-account">投资账户</label>
                    <select id="trade-account" value={tradeAccountId} onChange={(event) => setTradeAccountId(event.target.value)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                      <option value="">请选择投资账户</option>
                      {investmentAccounts.map((account) => <option key={account.id} value={account.id}>{account.name} · {account.currency}</option>)}
                    </select>
                  </div>
                  <div className="flex flex-col gap-2 md:col-span-2">
                    <span className="text-sm font-medium">标的</span>
                    {selectedTradeInstrument ? (
                      <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3">
                        <span>{selectedTradeInstrument.name} · {instrumentCode(selectedTradeInstrument) ?? '无外部代码'} · {selectedTradeInstrument.currency}</span>
                        <Button type="button" size="sm" variant="outline" onClick={() => setInstrumentToolsOpen(true)}>更换产品</Button>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center gap-3 rounded-md border border-dashed p-3">
                        <span className="text-sm text-muted-foreground">请先搜索并选择产品。</span>
                        <Button type="button" size="sm" variant="outline" onClick={() => setInstrumentToolsOpen(true)}><SearchIcon data-icon="inline-start" />打开产品搜索</Button>
                      </div>
                    )}
                  </div>
                  {tradeSide === 'DIVIDEND' ? (
                    <div className="flex flex-col gap-2">
                      <label htmlFor="trade-dividend">分红金额（{tradeCurrency}）</label>
                      <Input id="trade-dividend" inputMode="decimal" value={tradeDividendAmount} onChange={(event) => setTradeDividendAmount(event.target.value)} placeholder="0.00" />
                    </div>
                  ) : (
                    <>
                      <div className="flex flex-col gap-2">
                        <label htmlFor="trade-quantity">数量</label>
                        <Input id="trade-quantity" inputMode="decimal" value={tradeQuantity} onChange={(event) => setTradeQuantity(event.target.value)} placeholder="支持基金小数份额" />
                      </div>
                      <div className="flex flex-col gap-2">
                        <label htmlFor="trade-unit-price">成交单价（{tradeCurrency}）</label>
                        <Input id="trade-unit-price" inputMode="decimal" value={tradeUnitPrice} onChange={(event) => setTradeUnitPrice(event.target.value)} placeholder="0.0000" />
                      </div>
                    </>
                  )}
                  <div className="flex flex-col gap-2">
                    <label htmlFor="trade-fee">手续费（{tradeCurrency}）</label>
                    <Input id="trade-fee" inputMode="decimal" value={tradeFeeAmount} onChange={(event) => setTradeFeeAmount(event.target.value)} />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="trade-tax">税费（{tradeCurrency}）</label>
                    <Input id="trade-tax" inputMode="decimal" value={tradeTaxAmount} onChange={(event) => setTradeTaxAmount(event.target.value)} />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="trade-at">成交时间</label>
                    <Input id="trade-at" type="datetime-local" value={tradeAt} onChange={(event) => setTradeAt(event.target.value)} />
                    <p className="text-xs text-muted-foreground">业务日期由服务端按 {user?.timezone ?? '用户时区'} 固化。</p>
                  </div>
                  {tradeFormError ? <p className="text-sm text-destructive md:col-span-2" role="alert">{tradeFormError}</p> : null}
                  {tradeCreatedId ? <p className="text-sm text-muted-foreground md:col-span-2" role="status">投资交易已保存：{tradeCreatedId}</p> : null}
                  <div className="flex items-center gap-3 md:col-span-2">
                    <Button type="submit" disabled={tradeMutation.isPending}>
                      {tradeMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                      保存{TRADE_SIDES.find((option) => option.value === tradeSide)?.label}
                    </Button>
                    <p className="text-xs text-muted-foreground">提交携带幂等键，网络重试不会生成重复成交。</p>
                  </div>
                </form>
              )}
            </CardContent>
          </Card>
        </section>
      ) : null}

      <section className="flex flex-col gap-4" aria-label="投资持仓">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex flex-col gap-1">
            <h2 className="font-heading text-xl font-semibold tracking-tight">持仓</h2>
            <p className="text-sm text-muted-foreground">数量、移动平均成本、市值和未实现盈亏由有效投资交易与最新有效价格重建。</p>
          </div>
          {overviewQuery.data ? <Badge variant="outline">截至概览查询返回结果</Badge> : null}
        </div>
        {accountsQuery.isPending ? <Skeleton className="h-36 w-full" /> : accountsQuery.isError ? (
          <QueryError title="无法加载投资账户" error={accountsQuery.error} onRetry={() => void accountsQuery.refetch()} />
        ) : investmentAccounts.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon"><CalendarDaysIcon /></EmptyMedia>
              <EmptyTitle>还没有投资账户</EmptyTitle>
              <EmptyDescription>创建投资账户后，服务端才会返回持仓和估值数据。</EmptyDescription>
            </EmptyHeader>
            <EmptyContent><Button asChild><Link to="/accounts/new">创建投资账户</Link></Button></EmptyContent>
          </Empty>
        ) : (
          <div className="flex flex-col gap-4">
            {investmentAccounts.map((account, accountIndex) => {
              const positions = positionQueries[accountIndex]?.data?.data ?? []
              const positionsQuery = positionQueries[accountIndex]
              return (
                <Card key={account.id}>
                  <CardHeader>
                    <CardTitle>{account.name}</CardTitle>
                    <CardDescription>{account.institution ?? '投资账户'} · {account.accountType} · {account.currency}</CardDescription>
                    <CardAction><Badge variant="outline">{account.currentUserRole}</Badge></CardAction>
                  </CardHeader>
                  <CardContent>
                    {positionsQuery?.isPending ? <Skeleton className="h-28 w-full" /> : positionsQuery?.isError ? (
                      <QueryError title="无法加载该账户持仓" error={positionsQuery.error} onRetry={() => void positionsQuery.refetch()} />
                    ) : positions.length === 0 ? (
                      <p className="text-sm text-muted-foreground">服务端尚未返回持仓。</p>
                    ) : (
                      <ul className="flex flex-col gap-3">
                        {positions.map((position) => {
                          const instrumentIndex = instrumentIds.indexOf(position.instrumentId)
                          const instrumentQuery = instrumentQueries[instrumentIndex]
                          const priceQuery = priceQueries[instrumentIndex]
                          return (
                            <PositionRow
                              key={position.instrumentId}
                              position={position}
                              instrument={instrumentQuery?.data?.data}
                              price={latestPriceByInstrumentId.get(position.instrumentId)}
                              priceLoading={priceQuery?.isPending ?? false}
                              priceError={priceQuery?.isError ?? false}
                              onManualPrice={openManualPrice}
                            />
                          )
                        })}
                      </ul>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-4" aria-label="投资盈亏">
        <div className="flex flex-col gap-1">
          <h2 className="font-heading text-xl font-semibold tracking-tight">盈亏与收益</h2>
          <p className="text-sm text-muted-foreground">服务端按投资账户返回已实现、未实现、分红、费用、税费和 XIRR；页面不跨账户自行求和。</p>
        </div>
        {accountsQuery.isPending ? <Skeleton className="h-32 w-full" /> : investmentAccounts.length === 0 ? (
          <p className="text-sm text-muted-foreground">没有可展示的投资账户绩效。</p>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            {investmentAccounts.map((account, accountIndex) => {
              const performanceQuery = performanceQueries[accountIndex]
              const performance = performanceQuery?.data?.data
              return (
                <Card key={account.id}>
                  <CardHeader>
                    <CardTitle>{account.name}</CardTitle>
                    <CardDescription>服务端绩效 · {performance?.currency ?? account.currency}</CardDescription>
                  </CardHeader>
                  <CardContent>
                    {performanceQuery?.isPending ? <Skeleton className="h-24 w-full" /> : performanceQuery?.isError ? (
                      <QueryError title="无法加载账户绩效" error={performanceQuery.error} onRetry={() => void performanceQuery.refetch()} />
                    ) : performance ? (
                      <dl className="grid gap-3 text-sm sm:grid-cols-2">
                        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">已实现收益</dt><dd>{displaySignedMoney(performance.realizedProfit, performance.currency)}</dd></div>
                        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">未实现收益</dt><dd>{displaySignedMoney(performance.unrealizedProfit, performance.currency)}</dd></div>
                        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">分红</dt><dd>{displaySignedMoney(performance.dividends, performance.currency)}</dd></div>
                        <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">手续费 / 税费</dt><dd>{displayMoney(performance.fees, performance.currency)} / {displayMoney(performance.taxes, performance.currency)}</dd></div>
                        <div className="flex flex-col gap-1 sm:col-span-2"><dt className="text-xs text-muted-foreground">XIRR</dt><dd>{performance.xirrStatus === 'AVAILABLE' ? displayPercent(performance.xirr) : `—（${performance.xirrStatus}）`}</dd></div>
                      </dl>
                    ) : <p className="text-sm text-muted-foreground">服务端尚未返回绩效。</p>}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-4" aria-label="投资收益日历">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex flex-col gap-1">
            <h2 className="font-heading text-xl font-semibold tracking-tight">投资收益日历</h2>
            <p className="text-sm text-muted-foreground">按自然月查看全部投资或单一标的；买卖本金和组合外部转入转出不计为收益。</p>
          </div>
          <Badge variant="outline">Modified Dietz · 服务端计算</Badge>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>范围与月份</CardTitle>
            <CardDescription>完整性、估值修订和数据截至时间随服务端日历返回。</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] md:items-end">
              <div className="flex flex-col gap-2">
                <label htmlFor="return-scope">统计范围</label>
                <select id="return-scope" value={calendarScope} onChange={(event) => changeCalendarScope(event.target.value as components['parameters']['InvestmentReturnScopeType'])} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                  <option value="PORTFOLIO">全部投资</option>
                  <option value="INSTRUMENT">单一标的</option>
                </select>
              </div>
              {calendarScope === 'INSTRUMENT' ? (
                <div className="flex flex-col gap-2">
                  <label htmlFor="return-instrument">选择标的</label>
                  <select id="return-instrument" value={calendarInstrumentId} onChange={(event) => changeCalendarInstrument(event.target.value)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
                    <option value="">请选择标的</option>
                    {instruments.map((instrument) => <option key={instrument.id} value={instrument.id}>{instrument.name} · {instrumentCode(instrument) ?? instrument.id}</option>)}
                  </select>
                </div>
              ) : <div className="hidden md:block" aria-hidden="true" />}
              <div className="flex flex-col gap-2">
                <label htmlFor="return-display-mode">展示模式</label>
                <select id="return-display-mode" value={calendarDisplayMode} className="h-9 rounded-md border bg-transparent px-3 text-sm" onChange={(event) => setCalendarDisplayMode(event.target.value as 'AMOUNT' | 'RATE')}>
                  <option value="AMOUNT">收益金额</option>
                  <option value="RATE">收益率</option>
                </select>
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
              <div className="flex items-center gap-2">
                <Button type="button" variant="outline" size="icon" aria-label="上个月" onClick={() => changeCalendarMonth(shiftMonth(calendarMonth, -1))}><ChevronLeftIcon data-icon="inline-start" /></Button>
                <span className="min-w-28 text-center font-medium">{monthLabel(calendarMonth)}</span>
                <Button type="button" variant="outline" size="icon" aria-label="下个月" onClick={() => changeCalendarMonth(shiftMonth(calendarMonth, 1))}><ChevronRightIcon data-icon="inline-start" /></Button>
              </div>
              <span className="text-xs text-muted-foreground">基准币种：{calendarQuery.data?.data.baseCurrency ?? user?.baseCurrency ?? '—'}</span>
            </div>
          </CardContent>
        </Card>

        {calendarScope === 'INSTRUMENT' && calendarInstrumentId === '' ? (
          <Alert>
            <InfoIcon />
            <AlertTitle>请选择单一标的</AlertTitle>
            <AlertDescription>先搜索产品或等待持仓加载，再选择需要查看收益日历的股票、基金或 ETF。</AlertDescription>
          </Alert>
        ) : calendarQuery.isPending ? <Skeleton className="h-64 w-full" /> : calendarQuery.isError ? (
          <QueryError title="无法加载投资收益日历" error={calendarQuery.error} onRetry={() => void calendarQuery.refetch()} />
        ) : calendarQuery.data ? (
          <>
            <Card>
              <CardHeader>
                <CardTitle className="flex flex-wrap items-center gap-2">
                  {monthLabel(calendarQuery.data.data.month)} · {calendarQuery.data.data.scopeType === 'PORTFOLIO' ? '全部投资' : '单一标的'}
                  <Badge variant={calendarQuery.data.data.summaryStatus === 'COMPLETE' ? 'secondary' : calendarQuery.data.data.summaryStatus === 'UNAVAILABLE' ? 'destructive' : 'outline'}>{calendarQuery.data.data.summaryStatus}</Badge>
                </CardTitle>
                <CardDescription>数据截至 {displayTimestamp(calendarQuery.data.data.asOf)} · 最近重算 {displayTimestamp(calendarQuery.data.data.recalculatedAt)} · 估值修订 v{calendarQuery.data.data.valuationRevision}</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
                <MetricCard label="月度收益" value={displaySignedMoney(calendarQuery.data.data.monthlyProfit, calendarQuery.data.data.baseCurrency)} description={calendarQuery.data.data.summaryStatus === 'COMPLETE' ? '完整月份' : '不完整月份保持为空'} />
                <MetricCard label="月度收益率" value={displayPercent(calendarQuery.data.data.monthlyReturnRate)} description="服务端几何链接结果" />
                <MetricCard label="收益日" value={`${calendarQuery.data.data.profitDayCount} 天`} description="服务端计数" />
                <MetricCard label="亏损日" value={`${calendarQuery.data.data.lossDayCount} 天`} description="服务端计数" />
                <MetricCard label="真实零收益" value={`${calendarQuery.data.data.zeroDayCount} 天`} description="CALCULATED + 0" />
              </CardContent>
            </Card>

            {calendarQuery.data.data.dataQualityWarnings.length > 0 ? (
              <Alert>
                <TriangleAlertIcon />
                <AlertTitle>日历数据质量提示</AlertTitle>
                <AlertDescription className="flex flex-col gap-1">
                  {calendarQuery.data.data.dataQualityWarnings.map((warning) => <span key={warning.code}>{WARNING_LABELS[warning.code] ?? warning.code}（{warning.affectedCount} 项）</span>)}
                </AlertDescription>
              </Alert>
            ) : null}

            <Card>
              <CardHeader>
                <CardTitle>每日状态</CardTitle>
                <CardDescription>格子中的文字状态与 aria-label 同时说明数据质量；点击日期查看服务端明细。</CardDescription>
              </CardHeader>
              <CardContent>
                {calendarQuery.data.data.days.length === 0 ? <p className="text-sm text-muted-foreground">服务端未返回该月日期数据。</p> : (
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7" role="grid" aria-label={`${monthLabel(calendarQuery.data.data.month)}投资收益`}>
                    {calendarQuery.data.data.days.map((day, index) => {
                      const Icon = statusIcon(day.status)
                      const calculated = day.status === 'CALCULATED'
                      const zero = calculated && isZeroDecimal(day.dailyProfit)
                      const selected = selectedReturnDate === day.businessDate
                      const amountValue = calculated ? displaySignedMoney(day.dailyProfit, calendarQuery.data.data.baseCurrency) : '—'
                      const rateValue = calculated ? displayPercent(day.dailyReturnRate) : '—'
                      return (
                        <div key={day.businessDate} role="gridcell" aria-selected={selected}>
                          <Button
                            type="button"
                            variant={selected ? 'default' : 'outline'}
                            className="min-h-24 w-full flex-col items-start justify-start gap-2 p-3 text-left"
                            data-testid={`return-day-${day.businessDate}`}
                            aria-label={`${day.businessDate}，${RETURN_STATUS_LABELS[day.status]}，${calculated ? `收益金额 ${amountValue}，收益率 ${rateValue}` : RETURN_STATUS_DESCRIPTIONS[day.status]}`}
                            aria-pressed={selected}
                            ref={(element) => { dayButtonRefs.current[day.businessDate] = element }}
                            onClick={() => setSelectedReturnDate(day.businessDate)}
                            onKeyDown={(event) => handleCalendarKeyDown(event, index)}
                          >
                            <span className="flex w-full items-center justify-between gap-2 text-xs font-medium"><span>{day.businessDate.slice(-2)} 日</span><Icon data-icon="inline-start" /></span>
                            <span hidden={calendarDisplayMode !== 'AMOUNT'} className="w-full text-sm font-semibold">{amountValue}</span>
                            <span hidden={calendarDisplayMode !== 'RATE'} className="w-full text-sm font-semibold">{rateValue}</span>
                            <Badge variant={returnStatusVariant(day.status)}>{zero ? '真实零收益' : RETURN_STATUS_LABELS[day.status]}</Badge>
                            {day.missingInstrumentCount > 0 ? <span className="w-full text-xs text-muted-foreground">缺少估值 {day.missingInstrumentCount} 项</span> : null}
                          </Button>
                        </div>
                      )
                    })}
                  </div>
                )}
              </CardContent>
            </Card>
          </>
        ) : null}

        {selectedReturnDate ? (
          <Card data-testid="investment-return-detail">
            <CardHeader>
              <CardTitle>{selectedReturnDate} 收益明细</CardTitle>
              <CardDescription>{calendarScope === 'PORTFOLIO' ? '全部投资' : '单一标的'} · 点击日期后的服务端下钻结果</CardDescription>
              <CardAction><Button type="button" variant="ghost" size="sm" onClick={() => setSelectedReturnDate(null)}>关闭明细</Button></CardAction>
            </CardHeader>
            <CardContent>
              {returnDetailsQuery.isPending ? <Skeleton className="h-48 w-full" /> : returnDetailsQuery.isError ? (
                <QueryError title="无法加载日期明细" error={returnDetailsQuery.error} onRetry={() => void returnDetailsQuery.refetch()} />
              ) : returnDetailsQuery.data ? (
                <div className="flex flex-col gap-5">
                  <Alert>
                    {(() => { const Icon = statusIcon(returnDetailsQuery.data.data.status); return <Icon /> })()}
                    <AlertTitle>{RETURN_STATUS_LABELS[returnDetailsQuery.data.data.status]}</AlertTitle>
                    <AlertDescription>{RETURN_STATUS_DESCRIPTIONS[returnDetailsQuery.data.data.status]} 数据截至 {displayTimestamp(returnDetailsQuery.data.data.asOf)} · 估值修订 v{returnDetailsQuery.data.data.valuationRevision}</AlertDescription>
                  </Alert>
                  <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">日初市值</dt><dd>{displayMoney(returnDetailsQuery.data.data.beginValue, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">日终市值</dt><dd>{displayMoney(returnDetailsQuery.data.data.endValue, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">净现金流</dt><dd>{displaySignedMoney(returnDetailsQuery.data.data.netCashFlow, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">日收益</dt><dd>{displaySignedMoney(returnDetailsQuery.data.data.dailyProfit, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">日收益率</dt><dd>{displayPercent(returnDetailsQuery.data.data.dailyReturnRate)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">价格影响</dt><dd>{displaySignedMoney(returnDetailsQuery.data.data.marketEffect, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">汇率影响</dt><dd>{displaySignedMoney(returnDetailsQuery.data.data.fxEffect, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">分红</dt><dd>{displaySignedMoney(returnDetailsQuery.data.data.dividends, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">手续费</dt><dd>{displayMoney(returnDetailsQuery.data.data.fees, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                    <div className="flex flex-col gap-1"><dt className="text-xs text-muted-foreground">税费</dt><dd>{displayMoney(returnDetailsQuery.data.data.taxes, returnDetailsQuery.data.data.baseCurrency)}</dd></div>
                  </dl>
                  <div className="flex flex-col gap-2">
                    <h3 className="font-heading font-medium">收益贡献</h3>
                    <ContributionList contributions={returnDetailsQuery.data.data.contributions} currency={returnDetailsQuery.data.data.baseCurrency} />
                  </div>
                  {returnDetailsQuery.data.data.dataQualityWarnings.length > 0 ? <Alert><TriangleAlertIcon /><AlertTitle>明细数据质量提示</AlertTitle><AlertDescription>{returnDetailsQuery.data.data.dataQualityWarnings.map((warning) => `${WARNING_LABELS[warning.code] ?? warning.code}（${warning.affectedCount} 项）`).join('；')}</AlertDescription></Alert> : null}
                </div>
              ) : null}
            </CardContent>
          </Card>
        ) : null}
      </section>
    </main>
  )
}
