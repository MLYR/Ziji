import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-transaction-write-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')

try {
  // 结构化读取最终 bundle，锁定新启用交易写 operation 的实际 HTTP 响应集合，避免依赖 YAML 排版。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const operations = [
    ['/transactions', 'post', 'postTransaction'],
    ['/transactions/{transactionId}/revisions', 'post', 'reviseTransaction'],
    ['/transactions/{transactionId}/reversal', 'post', 'reverseTransaction'],
    ['/accounts/{accountId}/balance-adjustments', 'post', 'createBalanceAdjustment'],
  ]

  // 四个 operation 的实际错误契约一致；成功和失败响应均只能引用既有的全局响应组件。
  const expectedResponses = {
    201: '#/components/responses/TransactionCreated',
    400: '#/components/responses/BadRequest',
    401: '#/components/responses/Unauthenticated',
    403: '#/components/responses/Forbidden',
    404: '#/components/responses/NotFound',
    409: '#/components/responses/Conflict',
    422: '#/components/responses/BusinessRuleViolation',
    500: '#/components/responses/InternalError',
  }

  for (const [path, method, operationId] of operations) {
    const operation = contract.paths[path][method]
    assert.equal(operation.operationId, operationId, `${path} ${method} operationId 不得变化`)
    assert.deepEqual(
      Object.keys(operation.responses).sort(),
      Object.keys(expectedResponses).sort(),
      `${operationId} 响应键集合不符合实际契约`,
    )
    for (const [code, reference] of Object.entries(expectedResponses)) {
      assert.equal(operation.responses[code].$ref, reference, `${operationId} ${code} 必须引用 ${reference}`)
    }
  }
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('交易写操作错误响应契约静态检查通过。')
