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
  // 先注入 cookie 再导航：应用启动即发起 refresh，若先导航，后端会用 Max-Age=0
  // 清掉刚注入的会话 cookie，让「注入是否生效」变成时序赌博而非确定性断言。
  // 端口需与 playwright.integration.config.ts 的 ZIJI_WEB_PROXY_SMOKE_PORT 一致。
  const webHost = new URL(`http://localhost:${process.env.ZIJI_WEB_PROXY_SMOKE_PORT ?? '4176'}`).hostname
  await context.addCookies([
    { name: 'ziji_refresh', value: 'invalid-refresh-token', domain: webHost, path: '/api/v1', httpOnly: true, sameSite: 'Strict' },
    { name: 'ziji_csrf', value: 'csrf-browser-token', domain: webHost, path: '/', sameSite: 'Strict' },
  ])

  // 记录代理链路上实际收到的 Cookie 与 CSRF Header，作为同源边界的直接证据。
  const seen: { cookie: string | null; csrf: string | null }[] = []
  await page.route('**/api/v1/auth/web/sessions/refresh', async (route) => {
    seen.push({
      cookie: route.request().headers()['cookie'] ?? null,
      csrf: route.request().headers()['x-csrf-token'] ?? null,
    })
    await route.continue()
  })

  await page.goto('/dashboard')

  // 应用启动会发起一次 refresh；该请求必须携带同源 refresh 与可读 csrf Cookie。
  await expect.poll(() => seen.length, { timeout: 15_000 }).toBeGreaterThan(0)
  expect(seen[0]!.cookie).toContain('ziji_refresh=invalid-refresh-token')
  expect(seen[0]!.csrf).toBe('csrf-browser-token')

  // 无效刷新令牌不得被静默接受：后端应返回 401 并清除这两个会话 Cookie。
  const refreshed = await page.evaluate(async () => {
    const response = await fetch('/api/v1/auth/web/sessions/refresh', {
      method: 'POST',
      headers: { 'X-CSRF-Token': 'csrf-browser-token' },
      credentials: 'include',
    })
    return { status: response.status, problem: await response.json() }
  })
  expect(refreshed.status).toBe(401)
  expect(refreshed.problem).toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })

  await expect
    .poll(async () => (await context.cookies()).map((cookie) => cookie.name), { timeout: 10_000 })
    .not.toContain('ziji_refresh')
  expect((await context.cookies()).find((cookie) => cookie.name === 'ziji_csrf')).toBeUndefined()
})
