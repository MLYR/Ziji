import type { components, paths } from '@ziji/api-types';

// 两端共享同一份 OpenAPI 生成路由类型，业务客户端不得维护平行响应模型。
export type MobileApiPaths = paths;

export type ApiProblem = components['schemas']['Problem'];

export class ApiClientError extends Error {
  constructor(public readonly problem: ApiProblem) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiClientError';
  }
}

export interface MobileApiClientOptions {
  baseUrl: string;
  readAccessToken: () => Promise<string | null>;
}

function isApiProblem(value: unknown): value is ApiProblem {
  if (typeof value !== 'object' || value === null) return false;
  const problem = value as Record<string, unknown>;
  return typeof problem.title === 'string' && typeof problem.status === 'number';
}

export function createMobileApiClient({ baseUrl, readAccessToken }: MobileApiClientOptions) {
  return async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    const accessToken = await readAccessToken();
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

    // Mobile 使用响应体凭据和 Bearer access token，不复用 Web Cookie 会话策略。
    const response = await fetch(new URL(path, baseUrl), { ...init, headers, credentials: 'omit' });
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
      throw new ApiClientError(problem);
    }

    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  };
}
