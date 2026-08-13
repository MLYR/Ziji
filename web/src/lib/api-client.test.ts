import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiRequest } from './api-client'

describe('Web API client', () => {
  afterEach(() => {
    // 每个用例清理网络替身和 CSRF cookie，避免测试相互污染。
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  })

  it('为写请求携带 Cookie 会话和 CSRF token', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-test'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )

    await apiRequest('/api/v1/example', { method: 'POST', body: { value: 1 } })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/example', expect.objectContaining({ credentials: 'include' }))
    const request = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-test')
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
})
