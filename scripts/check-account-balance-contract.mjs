import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-account-balance-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')

try {
  // 使用项目已固定的 Redocly 结构化读取契约，避免断言受 YAML 排版或引用位置影响。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const balance = contract.paths['/accounts/{accountId}/balance'].get
  const schemas = contract.components.schemas
  const balanceSchema = schemas.AccountBalance
  const asOf = contract.components.parameters.AccountBalanceAsOf

  assert.equal(balance.operationId, 'getAccountBalance')
  assert.deepEqual(Object.keys(balance.responses).sort(), ['200', '400', '401', '404', '500'])
  assert.equal(balance.responses['200'].$ref, '#/components/responses/AccountBalanceOk')
  assert.equal(balance.responses['400'].$ref, '#/components/responses/BadRequest')
  assert.equal(balance.responses['401'].$ref, '#/components/responses/Unauthenticated')
  assert.equal(balance.responses['404'].$ref, '#/components/responses/NotFound')
  assert.equal(balance.responses['500'].$ref, '#/components/responses/InternalError')
  assert.deepEqual(balance['x-permission-matrix'], { read: ['OWNER', 'EDITOR', 'VIEWER'], write: [] })
  assert.deepEqual(balance['x-error-precedence'], ['AUTHENTICATION', 'REQUEST_VALIDATION', 'ACCOUNT_VISIBILITY', 'FACT_SNAPSHOT'])
  assert.deepEqual(balance['x-error-codes'], ['VALIDATION_ERROR', 'AUTHENTICATION_REQUIRED', 'RESOURCE_NOT_FOUND', 'INTERNAL_ERROR'])
  assert.deepEqual(balance['x-account-balance-read'], {
    asOf: 'SINGLE_CAPTURED_CLOCK_OR_EXPLICIT_UTC_INSTANT',
    ledgerFactSource: 'POSTED_PRIMARY_LEDGER_ENTRIES_BY_TRANSACTION_BUSINESS_DATE',
    liquidityFactSource: 'SAME_POSTGRESQL_SNAPSHOT_EFFECTIVE_LIQUIDITY_HOLDS',
    cache: 'FACT_READ_OR_PROVEN_EXACT_EQUIVALENCE_ONLY',
    asOfSequence: 'ZERO_SENTINEL_WITHOUT_GLOBAL_BALANCE_FACT_SEQUENCE',
    negativeAvailable: 'RETURN_UNCLAMPED',
  })
  assert.match(balance.description, /共同精确 asOf/)
  assert.match(balance.description, /ACTIVE/)
  assert.match(balance.description, /asOfSequence 固定返回明确哨兵 0/)

  assert.equal(balance.parameters.length, 1)
  assert.equal(balance.parameters[0].$ref, '#/components/parameters/AccountBalanceAsOf')
  assert.equal(asOf.name, 'asOf')
  assert.equal(asOf.in, 'query')
  assert.equal(asOf.required, false)
  assert.equal(asOf.schema.type, 'string')
  assert.equal(asOf.schema.format, 'date-time')
  assert.equal(asOf['x-runtime-format'], 'ISO_8601_DATE_TIME_WITH_UTC_OFFSET_OR_Z')
  assert.match(asOf.description, /带 Z 或明确 UTC offset/)

  assert.equal(balanceSchema.additionalProperties, false)
  assert.deepEqual(
    balanceSchema.required.sort(),
    ['accountId', 'currency', 'ledgerBalance', 'unavailableAmount', 'unavailableBreakdown', 'availableBalance', 'liquidityStatus', 'asOf', 'asOfSequence'].sort(),
  )
  assert.equal(balanceSchema.properties.ledgerBalance.$ref, '#/components/schemas/Money')
  assert.equal(balanceSchema.properties.unavailableAmount.$ref, '#/components/schemas/NonNegativeMoney')
  assert.equal(balanceSchema.properties.availableBalance.$ref, '#/components/schemas/Money')
  assert.match(balanceSchema.properties.ledgerBalance.description, /PRIMARY/)
  assert.match(balanceSchema.properties.unavailableAmount.description, /frozen、inTransit、reserved 三项之和/)
  assert.match(balanceSchema.properties.availableBalance.description, /ledgerBalance - unavailableAmount/)
  assert.match(balanceSchema.properties.availableBalance.description, /不得截断为零/)

  const breakdown = balanceSchema.properties.unavailableBreakdown
  assert.equal(breakdown.additionalProperties, false)
  assert.deepEqual(breakdown.required.sort(), ['frozen', 'inTransit', 'reserved'])
  assert.equal(breakdown.properties.frozen.$ref, '#/components/schemas/NonNegativeMoney')
  assert.equal(breakdown.properties.inTransit.$ref, '#/components/schemas/NonNegativeMoney')
  assert.equal(breakdown.properties.reserved.$ref, '#/components/schemas/NonNegativeMoney')
  assert.match(breakdown.description, /三项相加必须等于 unavailableAmount/)

  const liquidityStatus = balanceSchema.properties.liquidityStatus
  // STALE 描述投影新鲜度，能与正负可用余额并存，不能扩展为互斥的流动性数学状态。
  assert.deepEqual(liquidityStatus.enum, ['NORMAL', 'NEGATIVE_AVAILABLE'])
  for (const forbiddenStatus of ['STALE', 'HOLDS_EXCEED_BALANCE', 'LIQUIDITY_HOLDS_EXCEED_BALANCE']) {
    assert.equal(liquidityStatus.enum.includes(forbiddenStatus), false)
  }
  assert.match(liquidityStatus.description, /该字段不承载投影新鲜度/)
  assert.equal(balanceSchema.properties.asOf.type, 'string')
  assert.equal(balanceSchema.properties.asOf.format, 'date-time')
  assert.match(balanceSchema.properties.asOf.description, /唯一评估时点/)
  assert.equal(balanceSchema.properties.asOfSequence.type, 'integer')
  assert.equal(balanceSchema.properties.asOfSequence.const, 0)
  assert.equal(balanceSchema.properties.asOfSequence.minimum, 0)
  assert.match(balanceSchema.properties.asOfSequence.description, /固定为 0 的明确哨兵/)
  assert.match(balanceSchema.properties.asOfSequence.description, /不表示第 0 条业务变更/)

  assert.equal(schemas.AccountBalanceEnvelope.additionalProperties, false)
  assert.equal(schemas.AccountBalanceEnvelope.properties.data.$ref, '#/components/schemas/AccountBalance')
  assert.equal(schemas.AccountBalanceEnvelope.properties.meta.$ref, '#/components/schemas/ResponseMeta')
  assert.equal(
    contract.components.responses.AccountBalanceOk.content['application/json'].schema.$ref,
    '#/components/schemas/AccountBalanceEnvelope',
  )
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('账户余额时点、流动性状态与投影一致性契约静态检查通过。')
