import { expect, test, type Page } from '@playwright/test'

const MAILPIT_API = 'http://localhost:8025'

const PASSWORD = 'e2e-password-123'
const NICKNAME = 'E2E 用户'

function uniqueEmail(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 100000)}@example.test`
}

async function readLatestChallenge(page: Page, email: string): Promise<string> {
  // 邮件投递经过 outbox 轮询，轮询 Mailpit 直到到达。
  let message: { ID: string } | undefined
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const list = await (await page.request.get(`${MAILPIT_API}/api/v1/messages?limit=20`)).json() as {
      messages: Array<{ ID: string; To: Array<{ Address: string }> }>
    }
    message = list.messages.find((item) => item.To.some((to) => to.Address.toLowerCase() === email.toLowerCase()))
    if (message) break
    await page.waitForTimeout(1000)
  }
  expect(message, 'Mailpit 应收到验证码邮件').toBeTruthy()
  const detail = await (await page.request.get(`${MAILPIT_API}/api/v1/message/${message!.ID}`)).json() as { Text?: string; HTML?: string }
  const text = `${detail.Text ?? ''} ${detail.HTML ?? ''}`
  return text.replace(/<[^>]+>/g, ' ').match(/\d{6}/)?.[0] ?? (() => { throw new Error('验证码未在邮件正文中找到') })()
}

/** 注册与 Web 会话经 page.request 完成（与页面同 origin 同 Cookie）；UI 聚焦账户、记账、作废与 Dashboard 业务闭环。 */
async function registerAndLogin(page: Page): Promise<string> {
  const email = uniqueEmail()
  const deviceId = `e2e-device-${Date.now()}`
  const challengeResponse = await page.request.post('/api/v1/auth/registration-challenges', {
    data: { email, deviceId },
  })
  expect(challengeResponse.status(), '挑战签发应成功').toBe(202)
  const code = await readLatestChallenge(page, email)
  const registerResponse = await page.request.post('/api/v1/auth/register', {
    headers: { 'Idempotency-Key': `reg-${deviceId}` },
    data: {
      email, verificationCode: code, password: PASSWORD, nickname: NICKNAME,
      timezone: 'Asia/Shanghai', baseCurrency: 'CNY', locale: 'zh-CN',
    },
  })
  expect(registerResponse.status(), '注册应成功').toBe(201)
  const loginResponse = await page.request.post('/api/v1/auth/web/sessions', {
    data: { email, password: PASSWORD, deviceName: 'Playwright', deviceId: `${deviceId}-web` },
  })
  expect(loginResponse.status(), 'Web 登录应成功').toBe(201)
  await page.goto('/dashboard')
  await expect(page.getByText('净资产').first()).toBeVisible()
  return email
}

test('注册、创建账户并登记首笔支出，流水可见', async ({ page }) => {
  await registerAndLogin(page)

  // 创建现金账户（期初余额）
  await page.goto('/accounts/new')
  await page.getByLabel('大类').selectOption('ASSET')
  await page.getByLabel('子类型').selectOption('BANK')
  await page.locator('#account-name').fill('E2E 工资卡')
  await page.getByLabel('币种').selectOption('CNY')
  await page.getByRole('checkbox', { name: /登记期初余额/ }).check()
  await page.locator('#opening-amount').fill('10000')
  await page.getByRole('button', { name: '创建账户' }).click()
  await page.waitForURL(/\/accounts\//)
  await expect(page.getByText('10000').first()).toBeVisible()

  // 记一笔支出（分类使用默认「餐饮」）
  await page.goto('/transactions/new')
  await page.getByLabel('账户（资产或信用卡）').selectOption({ label: 'E2E 工资卡 · CNY' })
  await page.locator('#record-amount').fill('12.50')
  await page.getByLabel('分类').selectOption({ label: '餐饮' })
  await page.locator('#record-note').fill('E2E 午餐')
  await page.getByRole('button', { name: '保存交易' }).click()
  await expect(page.getByText('交易已保存')).toBeVisible()

  // 流水列表业务日期与「支出」类型可见；详情页展示备注
  await page.goto('/transactions')
  const firstRow = page.getByRole('link', { name: /支出/ }).first()
  await expect(firstRow).toBeVisible()
  await expect(page.getByText('已入账').first()).toBeVisible()
  await firstRow.click()
  await expect(page.getByText('交易详情')).toBeVisible()
})

test('交易作废产生冲正面，账户余额恢复且 Dashboard 显示真实指标', async ({ page }) => {
  await registerAndLogin(page)

  await page.goto('/accounts/new')
  await page.getByLabel('大类').selectOption('ASSET')
  await page.getByLabel('子类型').selectOption('BANK')
  await page.locator('#account-name').fill('E2E 作废卡')
  await page.getByLabel('币种').selectOption('CNY')
  await page.getByRole('checkbox', { name: /登记期初余额/ }).check()
  await page.locator('#opening-amount').fill('5000')
  await page.getByRole('button', { name: '创建账户' }).click()
  await page.waitForURL(/\/accounts\//)

  await page.goto('/transactions/new')
  await page.getByLabel('账户（资产或信用卡）').selectOption({ label: 'E2E 作废卡 · CNY' })
  await page.locator('#record-amount').fill('30')
  await page.getByLabel('分类').selectOption({ label: '餐饮' })
  await page.locator('#record-note').fill('E2E 待作废')
  await page.getByRole('button', { name: '保存交易' }).click()
  await expect(page.getByText('交易已保存')).toBeVisible()

  // 详情页作废
  await page.goto('/transactions')
  await page.getByRole('link', { name: /支出/ }).first().click()
  await expect(page.getByText('交易详情')).toBeVisible()
  await page.getByRole('button', { name: '作废此交易' }).click()
  await page.locator('#void-reason').fill('E2E 误记')
  await page.getByRole('button', { name: '确认作废' }).click()
  await expect(page.getByText('已作废').first()).toBeVisible()

  // 账户列表余额恢复（期初 5000 - 30 + 30 冲正 = 5000）
  await page.goto('/accounts')
  await expect(page.getByText('E2E 作废卡')).toBeVisible()
  await expect(page.getByText('5000').first()).toBeVisible()

  // Dashboard 显示真实指标（净资产 = 期初 + 收支净额 = 5000）
  await page.goto('/dashboard')
  await expect(page.getByText('净资产').first()).toBeVisible()
  await expect(page.getByText(/5000/).first()).toBeVisible()
})
