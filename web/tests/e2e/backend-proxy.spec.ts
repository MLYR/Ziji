import { expect, test } from '@playwright/test'

test('未认证请求经 Vite /api proxy 到达真实 Backend', async ({ page }) => {
  // 使用浏览器导航而非 route/mock，确保响应来自 Vite 之后的真实 HTTP 链路。
  const response = await page.goto('/api/v1/users/me')

  expect(response).not.toBeNull()
  expect(response?.status()).toBe(401)
  expect(response?.headers()['content-type']).toContain('application/problem+json')

  const problem = await response?.json() as Record<string, unknown>
  expect(problem).toMatchObject({
    status: 401,
    code: 'AUTHENTICATION_REQUIRED',
  })
  expect(problem.requestId).toEqual(expect.any(String))
})

test('Vite proxy 保留 Web refresh Cookie 与可读 CSRF Cookie 的同源边界', async ({ page, context }) => {
  await page.goto('/dashboard')
  const webOrigin = new URL(page.url()).origin
  const webHost = new URL(webOrigin).hostname
  await context.addCookies([
    { name: 'ziji_refresh', value: 'invalid-refresh-token', domain: webHost, path: '/api/v1', httpOnly: true, sameSite: 'Strict' },
    { name: 'ziji_csrf', value: 'csrf-browser-token', domain: webHost, path: '/', sameSite: 'Strict' },
  ])

  const result = await page.evaluate(async () => {
    const csrfToken = document.cookie.split('; ').find((cookie) => cookie.startsWith('ziji_csrf='))?.split('=')[1]
    const response = await fetch('/api/v1/auth/web/sessions/refresh', {
      method: 'POST',
      headers: { 'X-CSRF-Token': csrfToken ?? '' },
      credentials: 'include',
    })
    return {
      csrfToken,
      status: response.status,
      problem: await response.json(),
    }
  })

  expect(result.csrfToken).toBe('csrf-browser-token')
  expect(result.status).toBe(401)
  expect(result.problem).toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })
  expect((await context.cookies()).find((cookie) => cookie.name === 'ziji_refresh')).toBeUndefined()
  expect((await context.cookies()).find((cookie) => cookie.name === 'ziji_csrf')).toBeUndefined()
})
