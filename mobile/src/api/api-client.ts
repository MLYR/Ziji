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

export interface MobileTransactionApiClient {
  getTransaction(transactionId: string): Promise<TransactionEnvelope>;
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

export function createMobileTransactionApiClient(options: MobileApiClientOptions): MobileTransactionApiClient {
  const request = createMobileApiClient(options);

  return {
    getTransaction(transactionId) {
      return request<TransactionEnvelope>(`/api/v1/transactions/${encodeURIComponent(transactionId)}`, { method: 'GET' });
    },
  };
}
