import type { components } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type Instrument = components['schemas']['Instrument']
export type PriceSnapshot = components['schemas']['PriceSnapshot']
export type InvestmentTrade = components['schemas']['InvestmentTrade']
export type Position = components['schemas']['Position']
export type InvestmentPerformance = components['schemas']['InvestmentPerformance']
export type InvestmentOverview = components['schemas']['InvestmentOverview']
export type InvestmentReturnCalendar = components['schemas']['InvestmentReturnCalendar']
export type InvestmentReturnDay = components['schemas']['InvestmentReturnDay']
export type InvestmentReturnDayDetails = components['schemas']['InvestmentReturnDayDetails']
export type InvestmentReturnContribution = components['schemas']['InvestmentReturnContribution']
export type MarketDataStatus = components['schemas']['MarketDataStatusEnvelope']['data']

export type CreateInstrumentBody = components['schemas']['CreateInstrumentRequest']
export type CreatePriceBody = components['schemas']['CreatePriceRequest']
export type CreateInvestmentTradeBody = components['schemas']['CreateInvestmentTradeRequest']
export type InstrumentType = CreateInstrumentBody['instrumentType']
export type PriceType = CreatePriceBody['priceType']
export type TradeSide = CreateInvestmentTradeBody['side']
export type InvestmentReturnScopeType = components['parameters']['InvestmentReturnScopeType']

type InstrumentListEnvelope = components['schemas']['InstrumentListEnvelope']
type InstrumentEnvelope = components['schemas']['InstrumentEnvelope']
type PriceListEnvelope = components['schemas']['PriceListEnvelope']
type PriceEnvelope = components['schemas']['PriceEnvelope']
type MarketDataStatusEnvelope = components['schemas']['MarketDataStatusEnvelope']
type InvestmentTradeListEnvelope = components['schemas']['InvestmentTradeListEnvelope']
type InvestmentTradeEnvelope = components['schemas']['InvestmentTradeEnvelope']
type PositionListEnvelope = components['schemas']['PositionListEnvelope']
type InvestmentPerformanceEnvelope = components['schemas']['InvestmentPerformanceEnvelope']
type InvestmentOverviewEnvelope = components['schemas']['InvestmentOverviewEnvelope']
type InvestmentReturnCalendarEnvelope = components['schemas']['InvestmentReturnCalendarEnvelope']
type InvestmentReturnDayDetailsEnvelope = components['schemas']['InvestmentReturnDayDetailsEnvelope']

interface PriceFilters {
  dateFrom?: components['parameters']['DateFrom']
  dateTo?: components['parameters']['DateTo']
  cursor?: components['parameters']['Cursor']
  limit?: components['parameters']['Limit']
}

interface InvestmentTradeFilters extends PriceFilters {
  accountId?: components['parameters']['AccountIdQuery']
}

function withQuery(path: string, values: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  const encoded = query.toString()
  return encoded === '' ? path : `${path}?${encoded}`
}

export async function searchInstruments(query: string, cursor?: string): Promise<InstrumentListEnvelope> {
  return apiRequest<InstrumentListEnvelope>(withQuery('/api/v1/instruments/search', {
    q: query.trim(),
    limit: 20,
    cursor,
  }))
}

export async function createInstrument(idempotencyKey: string, body: CreateInstrumentBody): Promise<InstrumentEnvelope> {
  // 产品创建属于幂等写操作；键由页面按当前载荷生成，重复提交只重放同一结果。
  return apiRequest<InstrumentEnvelope>('/api/v1/instruments', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
}

export async function fetchInstrument(instrumentId: string): Promise<InstrumentEnvelope> {
  return apiRequest<InstrumentEnvelope>(`/api/v1/instruments/${encodeURIComponent(instrumentId)}`)
}

export async function listInstrumentPrices(instrumentId: string, filters: PriceFilters = {}): Promise<PriceListEnvelope> {
  return apiRequest<PriceListEnvelope>(withQuery(`/api/v1/instruments/${encodeURIComponent(instrumentId)}/prices`, {
    dateFrom: filters.dateFrom,
    dateTo: filters.dateTo,
    limit: filters.limit ?? 1,
    cursor: filters.cursor,
  }))
}

export async function createManualPrice(
  instrumentId: string,
  idempotencyKey: string,
  body: CreatePriceBody,
): Promise<PriceEnvelope> {
  // 手工价格仍然是服务端的价格事实；客户端只提交用户输入，不覆盖或重算历史价格。
  return apiRequest<PriceEnvelope>(`/api/v1/instruments/${encodeURIComponent(instrumentId)}/manual-prices`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
}

export async function fetchMarketDataStatus(): Promise<MarketDataStatusEnvelope> {
  return apiRequest<MarketDataStatusEnvelope>('/api/v1/market-data/status')
}

export async function listInvestmentTrades(filters: InvestmentTradeFilters = {}): Promise<InvestmentTradeListEnvelope> {
  return apiRequest<InvestmentTradeListEnvelope>(withQuery('/api/v1/investment-trades', {
    accountId: filters.accountId,
    dateFrom: filters.dateFrom,
    dateTo: filters.dateTo,
    limit: filters.limit ?? 50,
    cursor: filters.cursor,
  }))
}

export async function createInvestmentTrade(
  idempotencyKey: string,
  body: CreateInvestmentTradeBody,
): Promise<InvestmentTradeEnvelope> {
  // 买卖/分红的现金和持仓由服务端在同一事务内入账，页面不构造分录或本地余额。
  return apiRequest<InvestmentTradeEnvelope>('/api/v1/investment-trades', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
}

export async function listInvestmentPositions(
  accountId: string,
  asOf?: components['parameters']['AsOf'],
  cursor?: components['parameters']['Cursor'],
): Promise<PositionListEnvelope> {
  return apiRequest<PositionListEnvelope>(withQuery(`/api/v1/investment-accounts/${encodeURIComponent(accountId)}/positions`, {
    asOf,
    limit: 100,
    cursor,
  }))
}

export async function fetchInvestmentPerformance(
  accountId: string,
  dateFrom?: components['parameters']['DateFrom'],
  dateTo?: components['parameters']['DateTo'],
): Promise<InvestmentPerformanceEnvelope> {
  return apiRequest<InvestmentPerformanceEnvelope>(withQuery(`/api/v1/investment-accounts/${encodeURIComponent(accountId)}/performance`, {
    dateFrom,
    dateTo,
  }))
}

export async function fetchInvestmentOverview(asOf?: components['parameters']['AsOf']): Promise<InvestmentOverviewEnvelope> {
  return apiRequest<InvestmentOverviewEnvelope>(withQuery('/api/v1/investments/overview', { asOf }))
}

export async function fetchInvestmentReturnCalendar(
  month: components['parameters']['CalendarMonth'],
  scopeType: InvestmentReturnScopeType,
  instrumentId?: components['parameters']['InstrumentIdQuery'],
): Promise<InvestmentReturnCalendarEnvelope> {
  return apiRequest<InvestmentReturnCalendarEnvelope>(withQuery('/api/v1/investment-returns/calendar', {
    month,
    scopeType,
    instrumentId,
  }))
}

export async function fetchInvestmentReturnDayDetails(
  businessDate: components['parameters']['BusinessDate'],
  scopeType: InvestmentReturnScopeType,
  instrumentId?: components['parameters']['InstrumentIdQuery'],
): Promise<InvestmentReturnDayDetailsEnvelope> {
  return apiRequest<InvestmentReturnDayDetailsEnvelope>(withQuery(
    `/api/v1/investment-returns/calendar/${encodeURIComponent(businessDate)}/details`,
    { scopeType, instrumentId },
  ))
}
