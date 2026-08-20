import test from 'node:test'
import assert from 'node:assert/strict'

import { routeTask } from './agency-agent.mjs'

test('routes visual hierarchy work to UI Designer with UX Architect review', () => {
  const route = routeTask('设计 Dashboard 的视觉层级、颜色 Token 和组件状态')

  assert.equal(route.primaryRole, 'UI Designer')
  assert.equal(route.reviewerRole, 'UX Architect')
  assert.equal(route.confidence, 'high')
  assert.match(route.primaryPrompt, /Use the UI Designer agent\./)
  assert.match(route.reviewerPrompt, /Use the UX Architect agent\./)
})

test('routes OpenAPI work to API Tester and keeps review independent', () => {
  const route = routeTask('更新 OpenAPI operationId、ETag 和幂等契约')

  assert.equal(route.primaryRole, 'API Tester')
  assert.equal(route.reviewerRole, 'Code Reviewer')
  assert.match(route.primaryPrompt, /不执行 git add、git commit、git push/)
})

test('routes high-signal backend work to Backend Architect', () => {
  const route = routeTask('设计 Flyway 迁移、事务边界和 jOOQ 数据库访问')

  assert.equal(route.primaryRole, 'Backend Architect')
  assert.equal(route.reviewerRole, 'Code Reviewer')
})

test('falls back to Project Shepherd when no role keyword is present', () => {
  const route = routeTask('整理当前任务并确认下一步')

  assert.equal(route.primaryRole, 'Project Shepherd')
  assert.equal(route.reviewerRole, null)
  assert.equal(route.confidence, 'medium')
  assert.match(route.primaryPrompt, /Use the Project Shepherd agent\./)
})
