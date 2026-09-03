import type { components, operations, paths } from '@ziji/api-types';

// 两端共享同一份 OpenAPI 生成路由类型，业务客户端不得维护平行响应模型。
export type MobileApiPaths = paths;

export type ApiProblem = components['schemas']['Problem'];
export type SyncChangesEnvelope = components['schemas']['SyncChangeListEnvelope'];
export type SyncOperationsRequest = components['schemas']['ApplySyncOperationsRequest'];
export type SyncOperationsEnvelope = components['schemas']['SyncOperationResultsEnvelope'];
export type EmailChallengeRequest = components['schemas']['EmailChallengeRequest'];
export type RegisterRequest = components['schemas']['RegisterRequest'];
export type LoginRequest = components['schemas']['LoginRequest'];
export type MobileRefreshRequest = components['schemas']['MobileRefreshRequest'];
export type UserEnvelope = components['schemas']['UserEnvelope'];
export type MobileSessionEnvelope = components['schemas']['MobileSessionEnvelope'];
export type TransactionEnvelope = components['schemas']['TransactionEnvelope'];
export type Transaction = components['schemas']['Transaction'];
export type PostTransactionRequest = components['schemas']['PostTransactionRequest'];
export type DashboardEnvelope = components['schemas']['DashboardEnvelope'];
export type AccountEnvelope = components['schemas']['AccountEnvelope'];
export type AccountListEnvelope = components['schemas']['AccountListEnvelope'];
export type AccountBalanceEnvelope = components['schemas']['AccountBalanceEnvelope'];
export type CreateAccountRequest = components['schemas']['CreateAccountRequest'];
export type Account = components['schemas']['Account'];
export type AccountBalance = components['schemas']['AccountBalance'];
export type LiabilityDetail = components['schemas']['LiabilityDetail'];
export type LiabilityDetailEnvelope = components['schemas']['LiabilityDetailEnvelope'];
export type PutLiabilityDetailRequest = components['schemas']['PutLiabilityDetailRequest'];
export type StatisticsSeriesEnvelope = components['schemas']['StatisticsSeriesEnvelope'];
export type RegistrationChallengeEnvelope = operations['createRegistrationChallenge']['responses'][202]['content']['application/json'];

export interface MobileSyncApiClient {
  listSyncChanges(cursor: string | null): Promise<SyncChangesEnvelope>;
  applySyncOperations(request: SyncOperationsRequest): Promise<SyncOperationsEnvelope>;
}

export interface MobileAuthApiClient {
  createRegistrationChallenge(request: EmailChallengeRequest): Promise<RegistrationChallengeEnvelope>;
  registerUser(request: RegisterRequest, idempotencyKey: string): Promise<UserEnvelope>;
  createMobileSession(request: LoginRequest): Promise<MobileSessionEnvelope>;
  refreshMobileSession(request: MobileRefreshRequest): Promise<MobileSessionEnvelope>;
  revokeCurrentSession(): Promise<void>;
  getCurrentUser(): Promise<UserEnvelope>;
}

export type TransactionListEnvelope = components['schemas']['TransactionListEnvelope'];

export interface TransactionListFilters {
  accountId?: string;
  type?: 'INCOME' | 'EXPENSE' | 'REFUND' | 'TRANSFER' | 'ADJUSTMENT' | 'OPENING' | 'REVERSAL' | 'REPAYMENT';
  dateFrom?: string;
  dateTo?: string;
  categoryId?: string;
  cursor?: string | null;
}

export interface MobileTransactionApiClient {
  getTransaction(transactionId: string): Promise<TransactionEnvelope>;
  createTransaction(idempotencyKey: string, body: PostTransactionRequest): Promise<TransactionEnvelope>;
  listTransactions(limit: number, filters?: TransactionListFilters): Promise<TransactionListEnvelope>;
  reviseTransaction(
    transactionId: string,
    etag: string,
    idempotencyKey: string,
    body: components['schemas']['ReviseTransactionRequest'],
  ): Promise<TransactionEnvelope>;
  reverseTransaction(
    transactionId: string,
    etag: string,
    idempotencyKey: string,
    body: components['schemas']['ReasonRequest'],
  ): Promise<TransactionEnvelope>;
}

export interface MobileAccountsApiClient {
  listAccounts(limit: number): Promise<AccountListEnvelope>;
  getAccount(accountId: string): Promise<AccountEnvelope>;
  getAccountBalance(accountId: string): Promise<AccountBalanceEnvelope>;
  getLiabilityDetails(accountId: string): Promise<LiabilityDetailEnvelope>;
  putLiabilityDetails(
    accountId: string,
    precondition: { ifNoneMatch?: boolean; ifMatch?: string },
    idempotencyKey: string,
    body: PutLiabilityDetailRequest,
  ): Promise<LiabilityDetailEnvelope>;
  createAccount(idempotencyKey: string, body: CreateAccountRequest): Promise<components['schemas']['AccountCreatedEnvelope']>;
  updateAccount(accountId: string, etag: string, body: { name?: string; institution?: string | null }): Promise<AccountEnvelope>;
  archiveAccount(accountId: string, etag: string, idempotencyKey: string, body: { reason: string; confirmNonZeroBalance?: boolean }): Promise<AccountEnvelope>;
}

export interface MobileDashboardApiClient {
  getDashboard(): Promise<DashboardEnvelope>;
  getAssetStatistics(dateFrom: string, dateTo: string): Promise<StatisticsSeriesEnvelope>;
}

export class ApiClientError extends Error {
  constructor(
    public readonly problem: ApiProblem,
    public readonly retryAfterSeconds: number | null = null,
  ) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiClientError';
  }
}

export interface MobileApiClientOptions {
  baseUrl: string;
  readAccessToken: () => Promise<string | null>;
}

export type MobileRequestOptions = RequestInit & { includeBearer?: boolean };

function isApiProblem(value: unknown): value is ApiProblem {
  if (typeof value !== 'object' || value === null) return false;
  const problem = value as Record<string, unknown>;
  return typeof problem.title === 'string' && typeof problem.status === 'number';
}

export function createMobileApiClient({ baseUrl, readAccessToken }: MobileApiClientOptions) {
  return async function request<T>(path: string, init: MobileRequestOptions = {}): Promise<T> {
    const { includeBearer = true, ...fetchInit } = init;
    const headers = new Headers(fetchInit.headers);
    const accessToken = includeBearer ? await readAccessToken() : null;
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    if (fetchInit.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

    // Mobile 使用响应体凭据和 Bearer access token，不复用 Web Cookie 会话策略。
    const response = await fetch(new URL(path, baseUrl), { ...fetchInit, headers, credentials: 'omit' });
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => undefined);
      const problem: ApiProblem = isApiProblem(payload)
        ? payload
        : {
            type: 'about:blank',
            title: '请求失败',
            status: response.status,
            code: 'HTTP_ERROR',
            requestId: response.headers.get('X-Request-ID') ?? 'unknown',
          };
      const retryAfter = response.headers.get('Retry-After');
      throw new ApiClientError(problem, retryAfter === null ? null : Number.parseInt(retryAfter, 10) || null);
    }

    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  };
}

export function createMobileSyncApiClient(options: MobileApiClientOptions): MobileSyncApiClient {
  const request = createMobileApiClient(options);

  return {
    listSyncChanges(cursor) {
      const query = cursor === null ? '' : `?cursor=${encodeURIComponent(cursor)}`;
      return request<SyncChangesEnvelope>(`/api/v1/sync/changes${query}`, { method: 'GET' });
    },
    applySyncOperations(body) {
      return request<SyncOperationsEnvelope>('/api/v1/sync/operations', {
        method: 'POST',
        body: JSON.stringify(body),
      });
    },
  };
}

export function createMobileAuthApiClient(options: MobileApiClientOptions): MobileAuthApiClient {
  const request = createMobileApiClient(options);

  return {
    createRegistrationChallenge(body) {
      return request<RegistrationChallengeEnvelope>('/api/v1/auth/registration-challenges', {
        method: 'POST',
        includeBearer: false,
        body: JSON.stringify(body),
      });
    },
    registerUser(body, idempotencyKey) {
      return request<UserEnvelope>('/api/v1/auth/register', {
        method: 'POST',
        includeBearer: false,
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    createMobileSession(body) {
      return request<MobileSessionEnvelope>('/api/v1/auth/mobile/sessions', {
        method: 'POST',
        includeBearer: false,
        body: JSON.stringify(body),
      });
    },
    refreshMobileSession(body) {
      return request<MobileSessionEnvelope>('/api/v1/auth/mobile/sessions/refresh', {
        method: 'POST',
        includeBearer: false,
        body: JSON.stringify(body),
      });
    },
    revokeCurrentSession() {
      return request<void>('/api/v1/auth/sessions/current', { method: 'DELETE' });
    },
    getCurrentUser() {
      return request<UserEnvelope>('/api/v1/users/me', { method: 'GET' });
    },
  };
}

export function createMobileAccountsApiClient(options: MobileApiClientOptions): MobileAccountsApiClient {
  const request = createMobileApiClient(options);

  return {
    listAccounts(limit) {
      return request<AccountListEnvelope>(`/api/v1/accounts?limit=${limit}`, { method: 'GET' });
    },
    getAccount(accountId) {
      return request<AccountEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}`, { method: 'GET' });
    },
    getAccountBalance(accountId) {
      return request<AccountBalanceEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}/balance`, { method: 'GET' });
    },
    getLiabilityDetails(accountId) {
      return request<LiabilityDetailEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}/liability-details`, { method: 'GET' });
    },
    putLiabilityDetails(accountId, precondition, idempotencyKey, body) {
      // version=0 只代表稳定空详情：首次持久化必须用 If-None-Match:*，已有行必须用强 If-Match。
      const headers: Record<string, string> = { 'Idempotency-Key': idempotencyKey };
      if (precondition.ifNoneMatch) headers['If-None-Match'] = '*';
      if (precondition.ifMatch) headers['If-Match'] = precondition.ifMatch;
      return request<LiabilityDetailEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}/liability-details`, {
        method: 'PUT',
        headers,
        body: JSON.stringify(body),
      });
    },
    createAccount(idempotencyKey, body) {
      return request<components['schemas']['AccountCreatedEnvelope']>('/api/v1/accounts', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    updateAccount(accountId, etag, body) {
      return request<AccountEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}`, {
        method: 'PATCH',
        // 后端账户更新只接受 JSON Merge Patch；显式声明后通用层不会回退为 application/json。
        headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
        body: JSON.stringify(body),
      });
    },
    archiveAccount(accountId, etag, idempotencyKey, body) {
      return request<AccountEnvelope>(`/api/v1/accounts/${encodeURIComponent(accountId)}/archive`, {
        method: 'POST',
        headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
  };
}

export function createMobileDashboardApiClient(options: MobileApiClientOptions): MobileDashboardApiClient {
  const request = createMobileApiClient(options);

  return {
    getDashboard() {
      return request<DashboardEnvelope>('/api/v1/dashboard', { method: 'GET' });
    },
    getAssetStatistics(dateFrom, dateTo) {
      const query = new URLSearchParams({ dateFrom, dateTo, granularity: 'DAY' });
      return request<StatisticsSeriesEnvelope>(`/api/v1/statistics/assets?${query.toString()}`, { method: 'GET' });
    },
  };
}

export function createMobileTransactionApiClient(options: MobileApiClientOptions): MobileTransactionApiClient {
  const request = createMobileApiClient(options);

  return {
    getTransaction(transactionId) {
      return request<TransactionEnvelope>(`/api/v1/transactions/${encodeURIComponent(transactionId)}`, { method: 'GET' });
    },
    createTransaction(idempotencyKey, body) {
      return request<TransactionEnvelope>('/api/v1/transactions', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    listTransactions(limit, filters = {}) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (filters.accountId?.trim()) query.set('accountId', filters.accountId.trim());
      if (filters.type) query.set('type', filters.type);
      if (filters.dateFrom?.trim()) query.set('dateFrom', filters.dateFrom.trim());
      if (filters.dateTo?.trim()) query.set('dateTo', filters.dateTo.trim());
      if (filters.categoryId?.trim()) query.set('categoryId', filters.categoryId.trim());
      if (filters.cursor) query.set('cursor', filters.cursor);
      return request<TransactionListEnvelope>(`/api/v1/transactions?${query.toString()}`, { method: 'GET' });
    },
    reviseTransaction(transactionId, etag, idempotencyKey, body) {
      return request<TransactionEnvelope>(`/api/v1/transactions/${encodeURIComponent(transactionId)}/revisions`, {
        method: 'POST',
        headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    reverseTransaction(transactionId, etag, idempotencyKey, body) {
      return request<TransactionEnvelope>(`/api/v1/transactions/${encodeURIComponent(transactionId)}/reversal`, {
        method: 'POST',
        headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
  };
}
