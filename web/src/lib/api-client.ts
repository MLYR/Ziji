import type { components } from '@ziji/api-types'

import { getWebAccessToken } from '@/auth/auth-session'

export type ApiProblem = components['schemas']['Problem']

export class ApiClientError extends Error {
  readonly problem: ApiProblem
  readonly retryAfterSeconds: number | null

  constructor(problem: ApiProblem, retryAfterSeconds: number | null = null) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiClientError'
    this.problem = problem
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  auth?: boolean
}

function readCookie(name: string): string | undefined {
  // 浏览器只读取非 HttpOnly 的 CSRF cookie；刷新会话 cookie 始终由浏览器自动携带。
  const prefix = `${encodeURIComponent(name)}=`
  return document.cookie
    .split('; ')
    .find((cookie) => cookie.startsWith(prefix))
    ?.slice(prefix.length)
}

function isApiProblem(value: unknown): value is ApiProblem {
  if (typeof value !== 'object' || value === null) return false
  const problem = value as Record<string, unknown>
  return typeof problem.title === 'string' && typeof problem.status === 'number'
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { auth = true, ...requestOptions } = options
  const headers = new Headers(requestOptions.headers)
  const method = (requestOptions.method ?? 'GET').toUpperCase()

  if (requestOptions.body !== undefined) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    // 后端固定使用 ziji_csrf Cookie 与 X-CSRF-Token Header，避免登录后写请求被错误拒绝。
    const csrfToken = readCookie('ziji_csrf')
    if (csrfToken) headers.set('X-CSRF-Token', decodeURIComponent(csrfToken))
  }
  const accessToken = auth ? getWebAccessToken() : null
  if (accessToken && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${accessToken}`)

  // Web 会话只通过同源 Cookie 传输，避免将刷新凭据暴露给 JavaScript。
  const response = await fetch(path, {
    ...requestOptions,
    method,
    headers,
    credentials: 'include',
    body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body),
  })

  if (!response.ok) {
    const payload: unknown = await response.json().catch(() => undefined)
    const problem: ApiProblem = isApiProblem(payload)
      ? payload
      : {
          type: 'about:blank',
          title: '请求失败',
          status: response.status,
          code: 'HTTP_ERROR',
          requestId: response.headers.get('X-Request-ID') ?? 'unknown',
        }
    const retryAfterHeader = response.headers.get('Retry-After')
    const retryAfterSeconds = retryAfterHeader === null ? null : Number.parseInt(retryAfterHeader, 10) || null
    throw new ApiClientError(problem, retryAfterSeconds)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
