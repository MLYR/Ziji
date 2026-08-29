import type { components } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type Account = components['schemas']['Account']
export type AccountBalance = components['schemas']['AccountBalance']
export type Currency = components['schemas']['Currency']
export type LiabilityDetail = components['schemas']['LiabilityDetail']
export type PutLiabilityDetailBody = components['schemas']['PutLiabilityDetailRequest']

export interface AccountPage {
  accounts: Account[]
  nextCursor: string | null
  hasMore: boolean
}

/** 账户强 ETag 由版本号派生（"version"），更新/归档时以 If-Match 提交。 */
export function accountEtag(version: number): string {
  return `"${version}"`
}

/** 负债详情有独立版本，不能复用 Account.version 作为写入前置。 */
export function liabilityDetailEtag(version: number): string {
  return `"${version}"`
}

export async function listAccounts(): Promise<AccountPage> {
  const response = await apiRequest<{ data: Account[]; meta?: { nextCursor?: string | null; hasMore?: boolean } }>(
    '/api/v1/accounts?limit=100',
  )
  return {
    accounts: response.data,
    nextCursor: response.meta?.nextCursor ?? null,
    hasMore: response.meta?.hasMore ?? false,
  }
}

export async function fetchAccount(accountId: string): Promise<Account> {
  const response = await apiRequest<{ data: Account }>(`/api/v1/accounts/${accountId}`)
  return response.data
}

export async function fetchAccountBalance(accountId: string): Promise<AccountBalance> {
  const response = await apiRequest<{ data: AccountBalance }>(`/api/v1/accounts/${accountId}/balance`)
  return response.data
}

export async function fetchLiabilityDetails(accountId: string): Promise<LiabilityDetail> {
  const response = await apiRequest<{ data: LiabilityDetail }>(`/api/v1/accounts/${accountId}/liability-details`)
  return response.data
}

export async function putLiabilityDetails(
  accountId: string,
  version: number,
  idempotencyKey: string,
  body: PutLiabilityDetailBody,
): Promise<LiabilityDetail> {
  // version=0 只代表稳定空详情，首次持久化必须使用 If-None-Match 而不是 If-Match: "0"。
  const precondition = version === 0
    ? { 'If-None-Match': '*' }
    : { 'If-Match': liabilityDetailEtag(version) }
  const response = await apiRequest<{ data: LiabilityDetail }>(`/api/v1/accounts/${accountId}/liability-details`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey, ...precondition },
    body,
  })
  return response.data
}

export interface CreateAccountBody {
  accountClass: 'ASSET' | 'LIABILITY' | 'INVESTMENT'
  accountType: string
  name: string
  currency: Currency
  institution?: string | null
  note?: string | null
  openingBalance?: { amount: string; businessAt: string; note?: string | null } | null
}

export async function createAccount(idempotencyKey: string, body: CreateAccountBody): Promise<{ account: Account; openingTransactionId: string | null }> {
  const response = await apiRequest<{ data: { account: Account; openingTransactionId?: string | null } }>('/api/v1/accounts', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
  return {
    account: response.data.account,
    openingTransactionId: response.data.openingTransactionId ?? null,
  }
}

export async function updateAccount(accountId: string, etag: string, body: { name?: string; institution?: string | null }): Promise<Account> {
  const response = await apiRequest<{ data: Account }>(`/api/v1/accounts/${accountId}`, {
    method: 'PATCH',
    // 后端账户更新只消费 JSON Merge Patch，不能退回通用 application/json。
    headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
    body,
  })
  return response.data
}

export async function archiveAccount(
  accountId: string,
  etag: string,
  idempotencyKey: string,
  body: { reason: string; confirmNonZeroBalance?: boolean },
): Promise<Account> {
  const response = await apiRequest<{ data: Account }>(`/api/v1/accounts/${accountId}/archive`, {
    method: 'POST',
    headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
    body,
  })
  return response.data
}
