import { expect, test, type Page } from '@playwright/test'

const session = {
  id: 'session-1',
  deviceName: 'Web 浏览器',
  deviceId: null,
  createdAt: '2026-08-26T00:00:00Z',
  lastSeenAt: '2026-08-26T00:00:00Z',
  status: 'ACTIVE',
}

const user = {
  id: 'user-1',
  email: 'demo@example.com',
  nickname: '演示用户',
  timezone: 'Asia/Shanghai',
  baseCurrency: 'CNY',
  locale: 'zh-CN',
  amountFormat: 'STANDARD',
  status: 'ACTIVE',
  version: 1,
}

function responseBody(body: unknown) {
  return JSON.stringify(body)
}

async function stubLogin(page: Page) {
  // 端到端用例只替换认证边界，仍通过页面真实表单和内存会话进入受保护路由。
  await page.route('**/api/v1/auth/web/sessions/refresh', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: responseBody({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'AUTHENTICATION_REQUIRED', requestId: 'request-1' }),
    })
  })
  await page.route('**/api/v1/auth/web/sessions', async (route) => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: responseBody({ data: { session, accessToken: 'access-test', expiresIn: 1800 }, meta: {} }),
    })
  })
  await page.route('**/api/v1/users/me', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: responseBody({ data: user, meta: {} }) })
  })
}

async function fillLogin(page: Page) {
  await page.getByLabel(/邮箱地址/).fill(user.email)
  await page.getByLabel(/^密码/).fill('long-enough-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

test('应用壳登录后可进入总览并切换主题', async ({ page }) => {
  await stubLogin(page)
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await fillLogin(page)
  await expect(page.getByRole('heading', { name: '总览基础设施已就绪' })).toBeVisible()
  await page.getByRole('button', { name: '切换到浅色主题', exact: true }).click()
  await expect(page.locator('html')).toHaveClass(/light/)
})

test('有效 Web 会话在浏览器硬刷新后恢复，并可从壳退出', async ({ page, context }) => {
  await context.addCookies([
    { name: 'ziji_refresh', value: 'opaque-refresh-test-value', url: 'http://127.0.0.1:4175', httpOnly: true, sameSite: 'Strict' },
    { name: 'ziji_csrf', value: 'csrf-test', url: 'http://127.0.0.1:4175', sameSite: 'Strict' },
  ])
  let refreshCount = 0
  await page.route('**/api/v1/auth/web/sessions/refresh', async (route) => {
    refreshCount += 1
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-test')
    expect(route.request().headers().cookie).toContain('ziji_refresh=opaque-refresh-test-value')
    await route.fulfill({ status: 200, contentType: 'application/json', body: responseBody({ data: { session, accessToken: 'restored-access', expiresIn: 1800 }, meta: {} }) })
  })
  await page.route('**/api/v1/users/me', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: responseBody({ data: user, meta: {} }) })
  })
  await page.route('**/api/v1/auth/sessions/current', async (route) => {
    await route.fulfill({ status: 204, body: '' })
  })

  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '总览基础设施已就绪' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '总览基础设施已就绪' })).toBeVisible()
  expect(refreshCount).toBe(2)
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByRole('button', { name: '确认退出' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
})

test('设备会话管理入口可加载并标记当前设备', async ({ page }) => {
  await stubLogin(page)
  await page.route('**/api/v1/users/me/sessions?limit=20', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: responseBody({ data: [session], meta: { requestId: 'request-1', nextCursor: null, hasMore: false } }) })
  })

  await page.goto('/login')
  await fillLogin(page)
  await page.getByRole('button', { name: '设备与会话' }).click()
  await expect(page.getByText('这是当前设备')).toBeVisible()
  await expect(page.getByRole('button', { name: '退出全部设备' })).toBeVisible()
})

test('邮箱注册发送验证码并完成注册', async ({ page }) => {
  let registerPayload: Record<string, unknown> | undefined
  let idempotencyKey: string | undefined
  await page.route('**/api/v1/auth/registration-challenges', async (route) => {
    await route.fulfill({ status: 202, contentType: 'application/json', body: responseBody({ data: { expiresIn: 600 }, meta: {} }) })
  })
  await page.route('**/api/v1/auth/register', async (route) => {
    registerPayload = route.request().postDataJSON() as Record<string, unknown>
    idempotencyKey = route.request().headers()['idempotency-key']
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: responseBody({ data: { ...user, email: 'new@example.com', nickname: '新用户' }, meta: {} }),
    })
  })

  await page.goto('/register')
  await page.getByLabel(/邮箱地址/).fill('new@example.com')
  await page.getByRole('button', { name: '发送验证码', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('验证码已发送')
  await page.getByLabel(/邮箱验证码/).fill('123456')
  await page.locator('#register-password').fill('long-enough-password')
  await page.locator('#register-confirm-password').fill('long-enough-password')
  await page.getByLabel(/昵称/).fill('新用户')
  await page.locator('#register-timezone').selectOption('Asia/Shanghai')
  await page.locator('#register-currency').selectOption('CNY')
  await page.locator('#register-locale').selectOption('zh-CN')
  await page.getByRole('button', { name: '创建账户', exact: true }).click()

  await expect(page.getByRole('heading', { name: '注册完成' })).toBeVisible()
  expect(registerPayload).toMatchObject({
    email: 'new@example.com',
    verificationCode: '123456',
    password: 'long-enough-password',
    nickname: '新用户',
    timezone: 'Asia/Shanghai',
    baseCurrency: 'CNY',
    locale: 'zh-CN',
  })
  expect(idempotencyKey).toBeTruthy()
})

test('找回密码发送验证码并完成重置', async ({ page }) => {
  let resetPayload: Record<string, unknown> | undefined
  let idempotencyKey: string | undefined
  await page.route('**/api/v1/auth/password-reset-challenges', async (route) => {
    await route.fulfill({ status: 202, contentType: 'application/json', body: responseBody({ data: { expiresIn: 600 }, meta: {} }) })
  })
  await page.route('**/api/v1/auth/password-reset', async (route) => {
    resetPayload = route.request().postDataJSON() as Record<string, unknown>
    idempotencyKey = route.request().headers()['idempotency-key']
    await route.fulfill({ status: 204, body: '' })
  })

  await page.goto('/forgot-password')
  await page.getByLabel(/邮箱地址/).fill('demo@example.com')
  await page.getByRole('button', { name: '发送验证码', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('验证码已发送')
  await page.getByLabel(/^验证码/).fill('123456')
  await page.locator('#reset-new-password').fill('new-long-enough-password')
  await page.locator('#reset-confirm-password').fill('new-long-enough-password')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()

  await expect(page.getByRole('heading', { name: '密码已重置' })).toBeVisible()
  expect(resetPayload).toMatchObject({ email: 'demo@example.com', challengeCode: '123456', newPassword: 'new-long-enough-password' })
  expect(idempotencyKey).toBeTruthy()
})

test('登录错误不泄露邮箱是否存在', async ({ page }) => {
  await page.route('**/api/v1/auth/web/sessions', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: responseBody({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'INVALID_CREDENTIALS', requestId: 'request-1' }),
    })
  })

  await page.goto('/login')
  await fillLogin(page)
  await expect(page.getByText('邮箱或密码不正确，请检查后重试。')).toBeVisible()
  await expect(page.getByText(/邮箱不存在|账号不存在/)).not.toBeVisible()
})

test('登录请求处理中禁用按钮并显示加载文案', async ({ page }) => {
  let releaseSession!: () => void
  const sessionReady = new Promise<void>((resolve) => { releaseSession = resolve })
  await page.route('**/api/v1/auth/web/sessions', async (route) => {
    await sessionReady
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: responseBody({ data: { session, accessToken: 'access-test', expiresIn: 1800 }, meta: {} }),
    })
  })
  await page.route('**/api/v1/users/me', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: responseBody({ data: user, meta: {} }) })
  })

  await page.goto('/login')
  await page.getByLabel(/邮箱地址/).fill(user.email)
  await page.getByLabel(/^密码/).fill('long-enough-password')
  const submitButton = page.locator('form button[type="submit"]')
  await submitButton.click()

  await expect(submitButton).toBeDisabled()
  await expect(submitButton).toContainText('登录中…')
  releaseSession()
  await expect(page.getByRole('heading', { name: '总览基础设施已就绪' })).toBeVisible()
})
