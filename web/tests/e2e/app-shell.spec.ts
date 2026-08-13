import { expect, test } from '@playwright/test'

test('应用壳可进入总览并切换主题', async ({ page }) => {
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '总览基础设施已就绪' })).toBeVisible()
  await page.getByRole('button', { name: '切换到浅色主题' }).click()
  await expect(page.locator('html')).toHaveClass(/light/)
})
