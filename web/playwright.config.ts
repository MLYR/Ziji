import { defineConfig } from '@playwright/test'

const e2ePort = process.env.ZIJI_WEB_E2E_PORT ?? '4175'
const e2eBaseUrl = `http://127.0.0.1:${e2ePort}`

// Playwright 启动独立 Vite 预览端口，避免占用开发者正在使用的服务。
export default defineConfig({
  testDir: './tests/e2e',
  use: {
    baseURL: e2eBaseUrl,
    // 本地可复用已安装的稳定 Chrome；CI 未设置时仍使用 Playwright 自带 Chromium。
    launchOptions: process.env.ZIJI_PLAYWRIGHT_CHANNEL ? { channel: process.env.ZIJI_PLAYWRIGHT_CHANNEL } : {},
  },
  webServer: {
    command: `pnpm dev --host 127.0.0.1 --port ${e2ePort}`,
    url: e2eBaseUrl,
    reuseExistingServer: false,
  },
})
