import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { routeTask } from './agency-agent.mjs'

const SCRIPT_PATH = fileURLToPath(new URL('./agency-agent.mjs', import.meta.url))
const REPO_ROOT = resolve(dirname(SCRIPT_PATH), '..')

function runCli(args, env = {}) {
  return spawnSync(process.execPath, [SCRIPT_PATH, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: { ...process.env, ...env },
  })
}

function createFakeCodex(exitCode = 0) {
  const temporaryDir = mkdtempSync(join(tmpdir(), 'agency-agent-test-'))
  const capturePath = join(temporaryDir, 'codex-args')
  const commandPath = join(temporaryDir, 'codex')

  // 以替身记录 exec 参数，避免路由单测触发真实模型调用。
  writeFileSync(commandPath, `#!/bin/sh\nprintf '%s\\0' "$@" >> "$AGENCY_AGENT_CAPTURE"\nexit ${exitCode}\n`)
  chmodSync(commandPath, 0o755)

  return {
    env: {
      AGENCY_AGENT_CAPTURE: capturePath,
      PATH: `${temporaryDir}:${process.env.PATH}`,
    },
    readArgs: () => readFileSync(capturePath, 'utf8').split('\0').filter(Boolean),
    cleanup: () => rmSync(temporaryDir, { recursive: true, force: true }),
  }
}

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

test('routes generic backend implementation work to the installed Backend Architect', () => {
  const route = routeTask('为 Java service 编写 SQL 数据库适配器')

  assert.equal(route.primaryRole, 'Backend Architect')
  assert.equal(route.reviewerRole, 'Code Reviewer')
  assert.match(route.primaryPrompt, /Use the Backend Architect agent\./)
})

test('falls back to Project Shepherd when no role keyword is present', () => {
  const route = routeTask('整理当前任务并确认下一步')

  assert.equal(route.primaryRole, 'Project Shepherd')
  assert.equal(route.reviewerRole, null)
  assert.equal(route.confidence, 'medium')
  assert.match(route.primaryPrompt, /Use the Project Shepherd agent\./)
})

test('fails explicitly when a role TOML is absent', () => {
  const temporaryDir = mkdtempSync(join(tmpdir(), 'agency-agent-missing-role-'))

  try {
    const result = runCli(['--task', '更新 OpenAPI 契约', '--json'], { CODEX_AGENTS_DIR: temporaryDir })

    assert.equal(result.status, 2)
    assert.match(result.stderr, /角色未安装：API Tester/)
  } finally {
    rmSync(temporaryDir, { recursive: true, force: true })
  }
})

test('rejects --run without an explicit complete model ID before invoking codex', () => {
  const fakeCodex = createFakeCodex()

  try {
    const result = runCli(['--task', '更新 OpenAPI 契约', '--run'], fakeCodex.env)

    assert.equal(result.status, 2)
    assert.match(result.stderr, /--run 必须通过 --model 提供完整模型 ID/)
    assert.throws(() => fakeCodex.readArgs(), /ENOENT/)
  } finally {
    fakeCodex.cleanup()
  }
})

test('rejects a model alias that is not a complete model ID', () => {
  const fakeCodex = createFakeCodex()

  try {
    const result = runCli(['--task', '更新 OpenAPI 契约', '--run', '--model', 'latest'], fakeCodex.env)

    assert.equal(result.status, 2)
    assert.match(result.stderr, /--run 必须通过 --model 提供完整模型 ID/)
    assert.throws(() => fakeCodex.readArgs(), /ENOENT/)
  } finally {
    fakeCodex.cleanup()
  }
})

test('passes the repository path and complete model ID to codex exec', () => {
  const fakeCodex = createFakeCodex()

  try {
    const result = runCli(['--task', '更新 OpenAPI 契约', '--run', '--model', 'gpt-5.6-terra'], fakeCodex.env)

    assert.equal(result.status, 0)
    assert.deepEqual(fakeCodex.readArgs().slice(0, 5), ['exec', '--cd', REPO_ROOT, '--model', 'gpt-5.6-terra'])
  } finally {
    fakeCodex.cleanup()
  }
})

test('runs review only after a successful primary execution', () => {
  const successfulCodex = createFakeCodex()
  const failingCodex = createFakeCodex(9)

  try {
    const successful = runCli(['--task', '更新 OpenAPI 契约', '--run', '--review', '--model', 'gpt-5.6-terra'], successfulCodex.env)
    assert.equal(successful.status, 0)
    assert.equal(successfulCodex.readArgs().filter(argument => argument === 'exec').length, 2)

    const failing = runCli(['--task', '更新 OpenAPI 契约', '--run', '--review', '--model', 'gpt-5.6-terra'], failingCodex.env)
    assert.equal(failing.status, 9)
    assert.equal(failingCodex.readArgs().filter(argument => argument === 'exec').length, 1)
  } finally {
    successfulCodex.cleanup()
    failingCodex.cleanup()
  }
})
