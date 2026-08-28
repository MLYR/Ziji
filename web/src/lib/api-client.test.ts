import { afterEach, describe, expect, it, vi } from 'vitest'

import { clearWebSession, getWebAuthSnapshot, setWebAccessToken } from '@/auth/auth-session'
import { ApiClientError, apiRequest } from './api-client'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const authenticationRequired = { title: 'Unauthorized', status: 401, code: 'AUTHENTICATION_REQUIRED', requestId: 'request-1' }

describe('Web API client', () => {
  afterEach(() => {
    // 每个用例清理网络替身和 CSRF cookie，避免测试相互污染。
    clearWebSession()
    vi.restoreAllMocks()
    document.cookie = 'ziji_csrf=; Max-Age=0; path=/'
  })

  it('为写请求携带 Cookie 会话和 CSRF token', async () => {
    document.cookie = 'ziji_csrf=csrf-test; path=/'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )

    await apiRequest('/api/v1/example', { method: 'POST', body: { value: 1 } })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/example', expect.objectContaining({ credentials: 'include' }))
    const request = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('X-CSRF-Token')).toBe('csrf-test')
    expect(new Headers(request?.headers).get('Authorization')).toBeNull()
  })

  it('把 Problem Details 映射为稳定错误类型', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ title: 'Conflict', status: 409, code: 'IDEMPOTENCY_CONFLICT' }), {
        status: 409,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    )

    await expect(apiRequest('/api/v1/example')).rejects.toMatchObject({
      problem: { status: 409, code: 'IDEMPOTENCY_CONFLICT' },
    })
  })

  it('从内存认证态注入 Bearer token', async () => {
    setWebAccessToken('access-test')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )

    await apiRequest('/api/v1/users/me')

    const request = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('Authorization')).toBe('Bearer access-test')
  })

  it('公开请求显式跳过内存 Bearer token', async () => {
    setWebAccessToken('access-test')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )

    await apiRequest('/api/v1/auth/register', { method: 'POST', auth: false, body: { email: 'a@example.com' } })

    const request = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('Authorization')).toBeNull()
  })

  it('认证失效后刷新一次并使用轮换后的 CSRF 重放原请求', async () => {
    document.cookie = 'ziji_csrf=csrf-old; path=/'
    setWebAccessToken('expired-access')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (path) => {
      if (path === '/api/v1/auth/web/sessions/refresh') {
        document.cookie = 'ziji_csrf=csrf-new; path=/'
        return jsonResponse({ data: { session: { id: 'session-1' }, accessToken: 'fresh-access', expiresIn: 1800 }, meta: {} })
      }
      const authorization = new Headers(fetchMock.mock.calls.at(-1)?.[1]?.headers).get('Authorization')
      return authorization === 'Bearer fresh-access'
        ? jsonResponse({ id: 'ok' })
        : jsonResponse(authenticationRequired, 401)
    })

    await expect(apiRequest('/api/v1/example', { method: 'POST', body: { value: 1 }, headers: { 'Idempotency-Key': 'same-key', 'If-Match': '"7"' } })).resolves.toEqual({ id: 'ok' })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(['/api/v1/example', '/api/v1/auth/web/sessions/refresh', '/api/v1/example'])
    const retried = new Headers(fetchMock.mock.calls[2]?.[1]?.headers)
    expect(retried.get('Authorization')).toBe('Bearer fresh-access')
    expect(retried.get('X-CSRF-Token')).toBe('csrf-new')
    expect(retried.get('Idempotency-Key')).toBe('same-key')
    expect(retried.get('If-Match')).toBe('"7"')
  })

  it('并发认证失效共享同一个 Refresh 请求', async () => {
    setWebAccessToken('expired-access')
    let releaseRefresh!: (response: Response) => void
    const refreshPending = new Promise<Response>((resolve) => { releaseRefresh = resolve })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((path) => {
      if (path === '/api/v1/auth/web/sessions/refresh') return refreshPending
      const headers = new Headers(fetchMock.mock.calls.at(-1)?.[1]?.headers)
      return Promise.resolve(headers.get('Authorization') === 'Bearer fresh-access'
        ? jsonResponse({ id: String(path) })
        : jsonResponse(authenticationRequired, 401))
    })

    const first = apiRequest('/api/v1/one')
    const second = apiRequest('/api/v1/two')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    releaseRefresh(jsonResponse({ data: { session: { id: 'session-1' }, accessToken: 'fresh-access', expiresIn: 1800 }, meta: {} }))

    await expect(Promise.all([first, second])).resolves.toEqual([{ id: '/api/v1/one' }, { id: '/api/v1/two' }])
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/v1/auth/web/sessions/refresh')).toHaveLength(1)
  })

  it('Refresh 认证终态清理内存态且不递归重试', async () => {
    setWebAccessToken('expired-access')
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))

    await expect(apiRequest('/api/v1/example')).rejects.toBeInstanceOf(ApiClientError)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(getWebAuthSnapshot().accessToken).toBeNull()
  })

  it('Refresh 的网络或 5xx 失败不伪造退出状态', async () => {
    setWebAccessToken('expired-access')
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))
      .mockResolvedValueOnce(jsonResponse({ title: 'Unavailable', status: 503, code: 'INTERNAL_ERROR', requestId: 'request-1' }, 503))

    await expect(apiRequest('/api/v1/example')).rejects.toBeInstanceOf(ApiClientError)
    expect(getWebAuthSnapshot().accessToken).toBe('expired-access')

    vi.restoreAllMocks()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))
      .mockRejectedValueOnce(new TypeError('Network unavailable'))
    await expect(apiRequest('/api/v1/example')).rejects.toThrow('Network unavailable')
    expect(getWebAuthSnapshot().accessToken).toBe('expired-access')
  })

  it('原请求最多只重放一次，普通 403 与 INVALID_CREDENTIALS 不触发刷新', async () => {
    setWebAccessToken('expired-access')
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))
      .mockResolvedValueOnce(jsonResponse({ data: { session: { id: 'session-1' }, accessToken: 'fresh-access', expiresIn: 1800 }, meta: {} }))
      .mockResolvedValueOnce(jsonResponse(authenticationRequired, 401))

    await expect(apiRequest('/api/v1/example')).rejects.toBeInstanceOf(ApiClientError)
    expect(fetchMock).toHaveBeenCalledTimes(3)

    fetchMock.mockReset().mockResolvedValue(jsonResponse({ title: 'Forbidden', status: 403, code: 'PERMISSION_DENIED', requestId: 'request-1' }, 403))
    await expect(apiRequest('/api/v1/example')).rejects.toBeInstanceOf(ApiClientError)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    fetchMock.mockReset().mockResolvedValue(jsonResponse({ title: 'Unauthorized', status: 401, code: 'INVALID_CREDENTIALS', requestId: 'request-1' }, 401))
    await expect(apiRequest('/api/v1/auth/web/sessions', { method: 'POST', auth: false })).rejects.toBeInstanceOf(ApiClientError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
