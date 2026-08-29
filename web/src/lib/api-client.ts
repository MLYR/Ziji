import type { components } from '@ziji/api-types'

import { clearWebSession, getWebAccessToken, getWebAuthSnapshot, setWebSession } from '@/auth/auth-session'

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
  retryAuthentication?: boolean
}

type WebSessionData = components['schemas']['WebSessionEnvelope']['data']

let refreshPromise: Promise<WebSessionData | null> | null = null
let sessionTransition: Promise<void> | null = null
let webSessionGeneration = 0

export function getWebSessionGeneration() {
  return webSessionGeneration
}

async function waitForStableSessionTransition() {
  while (sessionTransition) {
    const activeTransition = sessionTransition
    await activeTransition.catch(() => undefined)
    if (sessionTransition === activeTransition) return
  }
}

export async function runWithWebSessionTransition<T>(operation: () => Promise<T>): Promise<T> {
  // 新登录或退出从开始即使此前请求失效，避免旧 401/Refresh 在 Cookie 已变化后继续执行。
  webSessionGeneration += 1
  const priorTransition = sessionTransition
  const pendingRefresh = refreshPromise
  let release!: () => void
  const completion = new Promise<void>((resolve) => { release = resolve })
  sessionTransition = completion

  try {
    await priorTransition?.catch(() => undefined)
    // 新登录/退出必须排在已发出的 Refresh 响应之后，确保自己的 Set-Cookie 最后写入浏览器。
    await pendingRefresh?.catch(() => undefined)
    return await operation()
  } finally {
    release()
    if (sessionTransition === completion) sessionTransition = null
  }
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

function isAuthenticationRequired(error: unknown) {
  return error instanceof ApiClientError
    && error.problem.status === 401
    && error.problem.code === 'AUTHENTICATION_REQUIRED'
}

async function requestOnce<T>(
  path: string,
  options: Omit<ApiRequestOptions, 'retryAuthentication'> & { auth: boolean },
  accessToken = options.auth ? getWebAccessToken() : null,
): Promise<T> {
  const { auth, ...requestOptions } = options
  const headers = new Headers(requestOptions.headers)
  const method = (requestOptions.method ?? 'GET').toUpperCase()

  if (requestOptions.body !== undefined && !headers.has('Content-Type')) {
    // 调用方可按 OpenAPI 声明专用 Media Type；未声明时才使用通用 JSON 默认值。
    headers.set('Content-Type', 'application/json')
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    // 后端固定使用 ziji_csrf Cookie 与 X-CSRF-Token Header，避免登录后写请求被错误拒绝。
    const csrfToken = readCookie('ziji_csrf')
    if (csrfToken) headers.set('X-CSRF-Token', decodeURIComponent(csrfToken))
  }
  if (auth) {
    // 认证 Header 只能来自当前内存 Token，重放时绝不能沿用调用方附带的旧 Bearer。
    headers.delete('Authorization')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  }

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

export async function refreshAccessSession(): Promise<WebSessionData | null> {
  if (refreshPromise) return refreshPromise
  const requestedGeneration = webSessionGeneration
  const requestedSessionId = getWebAuthSnapshot().session?.id ?? null
  if (sessionTransition) await waitForStableSessionTransition()
  if (refreshPromise) return refreshPromise
  if (requestedGeneration !== webSessionGeneration) return null

  refreshPromise = (async () => {
    try {
      const refreshed = await requestOnce<components['schemas']['WebSessionEnvelope']>('/api/v1/auth/web/sessions/refresh', {
        method: 'POST',
        auth: false,
      })
      if (requestedGeneration !== webSessionGeneration || (getWebAuthSnapshot().session?.id ?? null) !== requestedSessionId) {
        // 会话切换期间的过期 Refresh 响应不能覆盖已确认的新主体内存状态。
        return null
      }
      // 仅保存短期 Access Token 和稳定 session；HttpOnly Refresh Cookie 永不进入 JS 状态。
      setWebSession(refreshed.data)
      return refreshed.data
    } catch (error) {
      if (requestedGeneration !== webSessionGeneration) return null
      if (requestedGeneration === webSessionGeneration
        && (isAuthenticationRequired(error) || (error instanceof ApiClientError && error.problem.status === 403))) {
        // Refresh 的 403 无法区分 CSRF 细节，按安全边界 fail-closed，绝不继续重放敏感请求。
        clearWebSession()
      }
      throw error
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { auth = true, retryAuthentication = true, ...requestOptions } = options
  const requestedAccessToken = auth ? getWebAccessToken() : null
  const requestedSessionId = auth ? getWebAuthSnapshot().session?.id ?? null : null
  const requestedGeneration = webSessionGeneration

  try {
    return await requestOnce<T>(path, { ...requestOptions, auth }, requestedAccessToken)
  } catch (error) {
    if (!auth || !retryAuthentication || !isAuthenticationRequired(error)) throw error

    if (sessionTransition) await waitForStableSessionTransition()
    if (requestedGeneration !== webSessionGeneration) throw error
    const currentAuth = getWebAuthSnapshot()
    if (currentAuth.accessToken === requestedAccessToken) {
      if (await refreshAccessSession() === null) throw error
    } else if ((currentAuth.session?.id ?? null) !== requestedSessionId) {
      // 登出、换用户或换设备后，绝不能把旧主体的请求用新主体凭据重放。
      throw error
    }
    // 迟到的 401 只在同一稳定 Session 已刷新时重放，避免不必要的二次 Cookie 轮换。
    if (requestedGeneration !== webSessionGeneration
      || (requestedSessionId !== null && (getWebAuthSnapshot().session?.id ?? null) !== requestedSessionId)) throw error
    // 原请求仅重放一次，headers/body/幂等键/If-Match 均来自同一份 requestOptions。
    return requestOnce<T>(path, { ...requestOptions, auth })
  }
}
