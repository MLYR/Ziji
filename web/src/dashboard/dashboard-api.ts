import type { components } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type DashboardData = components['schemas']['Dashboard']
export type Account = components['schemas']['Account']
export type Transaction = components['schemas']['Transaction']

export interface StatisticsSeries {
  baseCurrency: string
  valuationRevision: number
  points: { businessDate: string; values: Record<string, string> }[]
}

export interface TransactionPage {
  transactions: Transaction[]
  nextCursor: string | null
  hasMore: boolean
}

export async function fetchDashboard(): Promise<DashboardData> {
  const response = await apiRequest<{ data: DashboardData }>('/api/v1/dashboard')
  return response.data
}

export async function fetchAssetStatistics(dateFrom: string, dateTo: string, granularity: 'DAY' | 'WEEK' | 'MONTH' | 'YEAR'): Promise<StatisticsSeries> {
  const response = await apiRequest<{ data: StatisticsSeries }>(
    `/api/v1/statistics/assets?dateFrom=${dateFrom}&dateTo=${dateTo}&granularity=${granularity}`,
  )
  return response.data
}

export async function fetchAccountStatistics(dateFrom: string, dateTo: string, granularity: 'DAY' | 'WEEK' | 'MONTH' | 'YEAR'): Promise<StatisticsSeries> {
  const response = await apiRequest<{ data: StatisticsSeries }>(
    `/api/v1/statistics/accounts?dateFrom=${dateFrom}&dateTo=${dateTo}&granularity=${granularity}`,
  )
  return response.data
}

export async function fetchRecentTransactions(limit = 5): Promise<TransactionPage> {
  const response = await apiRequest<{ data: Transaction[]; meta?: { nextCursor?: string | null; hasMore?: boolean } }>(
    `/api/v1/transactions?limit=${limit}`,
  )
  return {
    transactions: response.data,
    nextCursor: response.meta?.nextCursor ?? null,
    hasMore: response.meta?.hasMore ?? false,
  }
}
