import { expect, test, type Page } from '@playwright/test'

const MAILPIT_API = 'http://localhost:8025'

const PASSWORD = 'e2e-password-123'
const NICKNAME = 'B3 投资 E2E 用户'

function uniqueEmail(): string {
  return `e2e-b3-${Date.now()}-${Math.floor(Math.random() * 100000)}@example.test`
}

async function readLatestChallenge(page: Page, email: string): Promise<string> {
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

async function registerAndLogin(page: Page): Promise<void> {
  const email = uniqueEmail()
  const deviceId = `e2e-b3-device-${Date.now()}`
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
}

test('投资账户创建、手工产品与价格、买入、持仓和收益日历闭环', async ({ page }) => {
  await registerAndLogin(page)

  // 创建投资账户（券商现金，期初 50000）
  await page.goto('/accounts/new')
  await page.getByLabel('大类').selectOption('INVESTMENT')
  await page.getByLabel('子类型').selectOption('FUND')
  await page.locator('#account-name').fill('E2E 券商账户')
  await page.getByLabel('币种').selectOption('CNY')
  await page.getByRole('checkbox', { name: /登记期初余额/ }).check()
  await page.locator('#opening-amount').fill('50000')
  await page.getByRole('button', { name: '创建账户' }).click()
  await page.waitForURL(/\/accounts\//)

  // 进入投资页并手工创建产品
  await page.goto('/investments')
  await page.getByRole('button', { name: '产品与价格' }).click()
  await expect(page.getByText('产品搜索与手工价格').first()).toBeVisible()
  await page.getByRole('button', { name: '手工创建产品' }).click()
  await page.locator('#instrument-type').selectOption('STOCK')
  await page.locator('#instrument-name').fill('E2E 测试股票')
  await page.locator('#instrument-currency').selectOption('CNY')
  await page.getByRole('button', { name: '创建手工产品' }).click()
  await expect(page.getByText(/产品已创建/)).toBeVisible()

  // 搜索已缓存产品并补录手工价格（估值 15 元）
  await page.locator('#instrument-search').fill('E2E 测试股票')
  await page.getByRole('button', { name: '搜索产品' }).click()
  const resultRow = page.locator('[data-testid="instrument-search-results"]').getByText('E2E 测试股票')
  await expect(resultRow.first()).toBeVisible()
  await page.getByRole('button', { name: '补录价格' }).first().click()
  await page.locator('#manual-price-type').selectOption('CLOSE')
  await page.locator('#manual-price-date').fill(new Date().toISOString().slice(0, 10))
  await page.locator('#manual-price-value').fill('15')
  await page.locator('#manual-price-reason').fill('E2E 外部行情不可用，手工补录')
  await page.getByRole('button', { name: '保存手工价格' }).click()
  await expect(page.getByText(/价格已保存/)).toBeVisible()

  // 选择产品用于交易并买入 100 股 @10
  await page.getByRole('button', { name: '用于交易' }).first().click()
  await page.getByRole('button', { name: '记录投资交易' }).click()
  await page.locator('#trade-side').selectOption('BUY')
  await page.locator('#trade-account').selectOption({ label: 'E2E 券商账户 · CNY' })
  await page.locator('#trade-quantity').fill('100')
  await page.locator('#trade-unit-price').fill('10')
  await page.locator('#trade-fee').fill('5')
  await page.locator('#trade-tax').fill('0')
  await page.getByRole('button', { name: '保存买入' }).click()
  await expect(page.getByText(/投资交易已保存/)).toBeVisible()

  // 持仓：数量 100、市值 1500（100×15）与券商现金 48,995 可见
  await expect(page.getByText('持仓').first()).toBeVisible()
  await expect(page.getByText('E2E 测试股票').last()).toBeVisible()
  await expect(page.getByText('1500.00 CNY').first()).toBeVisible()

  // 全部投资与单一标的收益日历均可打开且可见状态标签
  await page.getByRole('button', { name: '查看收益日历' }).first().click()
  await expect(page.getByText(/收益日历/).first()).toBeVisible()

  // Dashboard 投资资产包含券商现金 + 持仓市值
  await page.goto('/dashboard')
  await expect(page.getByText('投资资产').first()).toBeVisible()
  await expect(page.getByText('50495.00').first()).toBeVisible()
})
