import { defineConfig } from '@playwright/test'

const integrationPort = process.env.ZIJI_WEB_PROXY_SMOKE_PORT ?? '4176'
const integrationBaseUrl = `http://localhost:${integrationPort}`

// 独立真实 Backend smoke，默认 Mock E2E 不需要启动 Spring Boot。
export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['backend-proxy.spec.ts', 'b1-core-flow.spec.ts', 'b3-investment-flow.spec.ts'],
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: integrationBaseUrl,
    launchOptions: process.env.ZIJI_PLAYWRIGHT_CHANNEL ? { channel: process.env.ZIJI_PLAYWRIGHT_CHANNEL } : {},
  },
  webServer: {
    command: `pnpm dev --host localhost --port ${integrationPort} --strictPort`,
    url: integrationBaseUrl,
    reuseExistingServer: false,
  },
})
