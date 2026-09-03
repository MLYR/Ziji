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
export type Category = components['schemas']['Category'];
export type CategoryListEnvelope = components['schemas']['CategoryListEnvelope'];
export type Tag = components['schemas']['Tag'];
export type TagListEnvelope = components['schemas']['TagListEnvelope'];
export type StatisticsSeriesEnvelope = components['schemas']['StatisticsSeriesEnvelope'];
export type Currency = components['schemas']['Currency'];
export type Instrument = components['schemas']['Instrument'];
export type InstrumentEnvelope = components['schemas']['InstrumentEnvelope'];
export type InstrumentListEnvelope = components['schemas']['InstrumentListEnvelope'];
export type PriceListEnvelope = components['schemas']['PriceListEnvelope'];
export type MarketDataStatusEnvelope = components['schemas']['MarketDataStatusEnvelope'];
export type InvestmentTrade = components['schemas']['InvestmentTrade'];
export type InvestmentTradeEnvelope = components['schemas']['InvestmentTradeEnvelope'];
export type InvestmentTradeListEnvelope = components['schemas']['InvestmentTradeListEnvelope'];
export type Position = components['schemas']['Position'];
export type PositionListEnvelope = components['schemas']['PositionListEnvelope'];
export type InvestmentPerformance = components['schemas']['InvestmentPerformance'];
export type InvestmentPerformanceEnvelope = components['schemas']['InvestmentPerformanceEnvelope'];
export type InvestmentOverview = components['schemas']['InvestmentOverview'];
export type InvestmentOverviewEnvelope = components['schemas']['InvestmentOverviewEnvelope'];
export type InvestmentReturnCalendar = components['schemas']['InvestmentReturnCalendar'];
export type InvestmentReturnCalendarEnvelope = components['schemas']['InvestmentReturnCalendarEnvelope'];
export type InvestmentReturnDay = components['schemas']['InvestmentReturnDay'];
export type InvestmentReturnDayDetails = components['schemas']['InvestmentReturnDayDetails'];
export type InvestmentReturnDayDetailsEnvelope = components['schemas']['InvestmentReturnDayDetailsEnvelope'];
export type CreateInstrumentRequest = components['schemas']['CreateInstrumentRequest'];
export type CreateInvestmentTradeRequest = components['schemas']['CreateInvestmentTradeRequest'];
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

export interface InstrumentPriceListFilters {
  dateFrom?: string;
  dateTo?: string;
  limit?: number;
  cursor?: string | null;
}

export interface InvestmentTradeListFilters {
  accountId?: string;
  dateFrom?: string;
  dateTo?: string;
  cursor?: string | null;
}

export interface InvestmentPositionListOptions {
  asOf?: string;
  limit?: number;
  cursor?: string | null;
}

export interface InvestmentPerformanceFilters {
  dateFrom?: string;
  dateTo?: string;
}

export interface MobileInvestmentApiClient {
  searchInstruments(query: string, limit?: number, cursor?: string | null): Promise<InstrumentListEnvelope>;
  createInstrument(idempotencyKey: string, body: CreateInstrumentRequest): Promise<InstrumentEnvelope>;
  getInstrument(instrumentId: string): Promise<InstrumentEnvelope>;
  listInstrumentPrices(instrumentId: string, filters?: InstrumentPriceListFilters): Promise<PriceListEnvelope>;
  getMarketDataStatus(): Promise<MarketDataStatusEnvelope>;
  listInvestmentTrades(limit?: number, filters?: InvestmentTradeListFilters): Promise<InvestmentTradeListEnvelope>;
  createInvestmentTrade(idempotencyKey: string, body: CreateInvestmentTradeRequest): Promise<InvestmentTradeEnvelope>;
  listInvestmentPositions(accountId: string, options?: InvestmentPositionListOptions): Promise<PositionListEnvelope>;
  getInvestmentPerformance(accountId: string, filters?: InvestmentPerformanceFilters): Promise<InvestmentPerformanceEnvelope>;
  getInvestmentOverview(asOf?: string): Promise<InvestmentOverviewEnvelope>;
  getInvestmentReturnCalendar(
    month: string,
    scopeType: 'PORTFOLIO' | 'INSTRUMENT',
    instrumentId?: string,
  ): Promise<InvestmentReturnCalendarEnvelope>;
  getInvestmentReturnDayDetails(
    businessDate: string,
    scopeType: 'PORTFOLIO' | 'INSTRUMENT',
    instrumentId?: string,
  ): Promise<InvestmentReturnDayDetailsEnvelope>;
}

export interface MobileCategoryApiClient {
  listCategories(scope: 'PERSONAL' | 'ACCOUNT'): Promise<CategoryListEnvelope>;
  createCategory(idempotencyKey: string, body: components['schemas']['CreateCategoryRequest']): Promise<components['schemas']['CategoryEnvelope']>;
  patchCategory(categoryId: string, etag: string, body: { name?: string; status?: 'ACTIVE' | 'INACTIVE' }): Promise<components['schemas']['CategoryEnvelope']>;
  mergeCategory(categoryId: string, etag: string, idempotencyKey: string, targetCategoryId: string): Promise<components['schemas']['CategoryEnvelope']>;
  listTags(): Promise<TagListEnvelope>;
  createTag(idempotencyKey: string, body: { name: string }): Promise<components['schemas']['TagEnvelope']>;
  patchTag(tagId: string, etag: string, body: { name?: string; status?: 'ACTIVE' | 'INACTIVE' }): Promise<components['schemas']['TagEnvelope']>;
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

export function createMobileInvestmentApiClient(options: MobileApiClientOptions): MobileInvestmentApiClient {
  const request = createMobileApiClient(options);

  return {
    searchInstruments(query, limit = 20, cursor = null) {
      const params = new URLSearchParams({ q: query });
      params.set('limit', String(limit));
      if (cursor) params.set('cursor', cursor);
      return request<InstrumentListEnvelope>(`/api/v1/instruments/search?${params.toString()}`, { method: 'GET' });
    },
    createInstrument(idempotencyKey, body) {
      return request<InstrumentEnvelope>('/api/v1/instruments', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    getInstrument(instrumentId) {
      return request<InstrumentEnvelope>(`/api/v1/instruments/${encodeURIComponent(instrumentId)}`, { method: 'GET' });
    },
    listInstrumentPrices(instrumentId, filters = {}) {
      const query = new URLSearchParams();
      if (filters.dateFrom?.trim()) query.set('dateFrom', filters.dateFrom.trim());
      if (filters.dateTo?.trim()) query.set('dateTo', filters.dateTo.trim());
      if (filters.limit !== undefined) query.set('limit', String(filters.limit));
      if (filters.cursor) query.set('cursor', filters.cursor);
      const suffix = query.toString();
      return request<PriceListEnvelope>(`/api/v1/instruments/${encodeURIComponent(instrumentId)}/prices${suffix ? `?${suffix}` : ''}`, { method: 'GET' });
    },
    getMarketDataStatus() {
      return request<MarketDataStatusEnvelope>('/api/v1/market-data/status', { method: 'GET' });
    },
    listInvestmentTrades(limit = 50, filters = {}) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (filters.accountId?.trim()) query.set('accountId', filters.accountId.trim());
      if (filters.dateFrom?.trim()) query.set('dateFrom', filters.dateFrom.trim());
      if (filters.dateTo?.trim()) query.set('dateTo', filters.dateTo.trim());
      if (filters.cursor) query.set('cursor', filters.cursor);
      return request<InvestmentTradeListEnvelope>(`/api/v1/investment-trades?${query.toString()}`, { method: 'GET' });
    },
    createInvestmentTrade(idempotencyKey, body) {
      return request<InvestmentTradeEnvelope>('/api/v1/investment-trades', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    listInvestmentPositions(accountId, options = {}) {
      const query = new URLSearchParams();
      if (options.asOf?.trim()) query.set('asOf', options.asOf.trim());
      if (options.limit !== undefined) query.set('limit', String(options.limit));
      if (options.cursor) query.set('cursor', options.cursor);
      const suffix = query.toString();
      return request<PositionListEnvelope>(`/api/v1/investment-accounts/${encodeURIComponent(accountId)}/positions${suffix ? `?${suffix}` : ''}`, { method: 'GET' });
    },
    getInvestmentPerformance(accountId, filters = {}) {
      const query = new URLSearchParams();
      if (filters.dateFrom?.trim()) query.set('dateFrom', filters.dateFrom.trim());
      if (filters.dateTo?.trim()) query.set('dateTo', filters.dateTo.trim());
      const suffix = query.toString();
      return request<InvestmentPerformanceEnvelope>(`/api/v1/investment-accounts/${encodeURIComponent(accountId)}/performance${suffix ? `?${suffix}` : ''}`, { method: 'GET' });
    },
    getInvestmentOverview(asOf) {
      const query = new URLSearchParams();
      if (asOf?.trim()) query.set('asOf', asOf.trim());
      const suffix = query.toString();
      return request<InvestmentOverviewEnvelope>(`/api/v1/investments/overview${suffix ? `?${suffix}` : ''}`, { method: 'GET' });
    },
    getInvestmentReturnCalendar(month, scopeType, instrumentId) {
      const query = new URLSearchParams({ month, scopeType });
      if (instrumentId) query.set('instrumentId', instrumentId);
      return request<InvestmentReturnCalendarEnvelope>(`/api/v1/investment-returns/calendar?${query.toString()}`, { method: 'GET' });
    },
    getInvestmentReturnDayDetails(businessDate, scopeType, instrumentId) {
      const query = new URLSearchParams({ scopeType });
      if (instrumentId) query.set('instrumentId', instrumentId);
      return request<InvestmentReturnDayDetailsEnvelope>(`/api/v1/investment-returns/calendar/${encodeURIComponent(businessDate)}/details?${query.toString()}`, { method: 'GET' });
    },
  };
}

export function createMobileCategoryApiClient(options: MobileApiClientOptions): MobileCategoryApiClient {
  const request = createMobileApiClient(options);

  return {
    listCategories(scope) {
      return request<CategoryListEnvelope>(`/api/v1/categories?scope=${scope}&limit=100`, { method: 'GET' });
    },
    createCategory(idempotencyKey, body) {
      return request<components['schemas']['CategoryEnvelope']>('/api/v1/categories', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    patchCategory(categoryId, etag, body) {
      return request<components['schemas']['CategoryEnvelope']>(`/api/v1/categories/${encodeURIComponent(categoryId)}`, {
        method: 'PATCH',
        headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
        body: JSON.stringify(body),
      });
    },
    mergeCategory(categoryId, etag, idempotencyKey, targetCategoryId) {
      return request<components['schemas']['CategoryEnvelope']>(`/api/v1/categories/${encodeURIComponent(categoryId)}/merge`, {
        method: 'POST',
        headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ targetCategoryId }),
      });
    },
    listTags() {
      return request<TagListEnvelope>('/api/v1/tags?limit=100', { method: 'GET' });
    },
    createTag(idempotencyKey, body) {
      return request<components['schemas']['TagEnvelope']>('/api/v1/tags', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    },
    patchTag(tagId, etag, body) {
      return request<components['schemas']['TagEnvelope']>(`/api/v1/tags/${encodeURIComponent(tagId)}`, {
        method: 'PATCH',
        headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
        body: JSON.stringify(body),
      });
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
