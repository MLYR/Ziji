import { afterEach, describe, expect, it, vi } from 'vitest'

import { clearWebSession } from '@/auth/auth-session'
import { updateAccount } from '@/accounts/accounts-api'

describe('账户 API', () => {
  afterEach(() => {
    clearWebSession()
    vi.restoreAllMocks()
  })

  it('更新账户经真实请求层发送 merge-patch Media Type', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ data: { id: 'account-1' } }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )

    await updateAccount('account-1', '"2"', { name: '新名称' })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/accounts/account-1', expect.objectContaining({ method: 'PATCH' }))
    const request = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('If-Match')).toBe('"2"')
    expect(new Headers(request?.headers).get('Content-Type')).toBe('application/merge-patch+json')
    expect(request?.body).toBe(JSON.stringify({ name: '新名称' }))
  })
})
