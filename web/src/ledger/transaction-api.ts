import type { components } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type Account = components['schemas']['Account']
export type TransactionView = components['schemas']['Transaction']

export interface AccountPage {
  accounts: Account[]
  nextCursor: string | null
  hasMore: boolean
}

interface PageMeta {
  requestId: string
  nextCursor?: string | null
  hasMore?: boolean
}

/** 读取账户首页（limit 上限 100）用于记账选择器；B1 账户规模足够，翻页由流水页任务承担。 */
export async function listAccounts(): Promise<AccountPage> {
  const response = await apiRequest<{ data: Account[]; meta: PageMeta }>('/api/v1/accounts?limit=100')
  return {
    accounts: response.data,
    nextCursor: response.meta?.nextCursor ?? null,
    hasMore: response.meta?.hasMore ?? false,
  }
}

export type PostTransactionBody = components['schemas']['PostTransactionRequest']
export type Transaction = components['schemas']['Transaction']
export type TransactionType = Transaction['type']

export interface BalanceAdjustmentBody {
  actualBalance: string
  businessAt: string
  timezone: string
  reason: string
}

function createdEnvelope(response: { data: { id?: string } }): string | null {
  return response.data?.id ?? null
}

export async function createTransaction(idempotencyKey: string, body: PostTransactionBody): Promise<string | null> {
  const response = await apiRequest<{ data: { id?: string } }>('/api/v1/transactions', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
  return createdEnvelope(response)
}

export async function createBalanceAdjustment(
  accountId: string,
  idempotencyKey: string,
  body: BalanceAdjustmentBody,
): Promise<string | null> {
  const response = await apiRequest<{ data: { id?: string } }>(
    `/api/v1/accounts/${accountId}/balance-adjustments`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body,
    },
  )
  return createdEnvelope(response)
}

export interface TransactionPage {
  transactions: Transaction[]
  nextCursor: string | null
  hasMore: boolean
}

export interface TransactionFilters {
  accountId?: string
  categoryId?: string
  type?: TransactionType
  dateFrom?: string
  dateTo?: string
  limit?: number
  cursor?: string
}

export async function listTransactions(filters: TransactionFilters): Promise<TransactionPage> {
  const query = new URLSearchParams()
  if (filters.accountId) query.set('accountId', filters.accountId)
  if (filters.categoryId) query.set('categoryId', filters.categoryId)
  if (filters.type) query.set('type', filters.type)
  if (filters.dateFrom) query.set('dateFrom', filters.dateFrom)
  if (filters.dateTo) query.set('dateTo', filters.dateTo)
  query.set('limit', String(filters.limit ?? 50))
  if (filters.cursor) query.set('cursor', filters.cursor)

  const response = await apiRequest<{ data: Transaction[]; meta: { requestId: string; nextCursor: string | null; hasMore: boolean } }>(
    `/api/v1/transactions?${query.toString()}`,
  )
  return {
    transactions: response.data,
    nextCursor: response.meta.nextCursor,
    hasMore: response.meta.hasMore,
  }
}

export async function fetchTransaction(transactionId: string): Promise<Transaction> {
  const response = await apiRequest<{ data: Transaction }>(`/api/v1/transactions/${transactionId}`)
  return response.data
}

/** 详情资源的强 ETag 固定由契约版本号派生。 */
export function transactionEtag(version: number): string {
  return `"${version}"`
}

export interface ReviseTransactionBody {
  reason: string
  replacement: PostTransactionBody
}

export async function reviseTransaction(
  transactionId: string,
  etag: string,
  idempotencyKey: string,
  body: ReviseTransactionBody,
): Promise<Transaction> {
  const response = await apiRequest<{ data: Transaction }>(`/api/v1/transactions/${transactionId}/revisions`, {
    method: 'POST',
    headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
    body,
  })
  return response.data
}

export async function reverseTransaction(
  transactionId: string,
  etag: string,
  idempotencyKey: string,
  body: { reason: string },
): Promise<Transaction> {
  const response = await apiRequest<{ data: Transaction }>(`/api/v1/transactions/${transactionId}/reversal`, {
    method: 'POST',
    headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
    body,
  })
  return response.data
}
