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
