import type { components } from '@ziji/api-types'

export type ApiProblem = components['schemas']['Problem']

export class ApiClientError extends Error {
  readonly problem: ApiProblem

  constructor(problem: ApiProblem) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiClientError'
    this.problem = problem
  }
}

interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
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
  const headers = new Headers(options.headers)
  const method = (options.method ?? 'GET').toUpperCase()

  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrfToken = readCookie('XSRF-TOKEN')
    if (csrfToken) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrfToken))
  }

  // Web 会话只通过同源 Cookie 传输，避免将刷新凭据暴露给 JavaScript。
  const response = await fetch(path, {
    ...options,
    method,
    headers,
    credentials: 'include',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
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
    throw new ApiClientError(problem)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
