import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-account-archive-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')
const securityConfiguration = readFileSync(
  join(repositoryRoot, 'backend/src/main/java/app/ziji/auth/infrastructure/SecurityConfiguration.java'),
  'utf8',
)

try {
  // 使用 Redocly bundle 读取最终契约，避免检查依赖 YAML 排版或隐式引用解析顺序。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const archive = contract.paths['/accounts/{accountId}/archive'].post
  const request = contract.components.schemas.ArchiveAccountRequest
  const nonZero = contract.components.responses.NonZeroBalanceConfirmationRequired
  const nonZeroSchema = nonZero.content['application/problem+json'].schema

  assert.equal(archive.operationId, 'archiveAccount')
  assert.match(securityConfiguration, /"\/api\/v1\/accounts\/\*\/archive"/)
  assert.match(securityConfiguration, /path\.matches\("\/api\/v1\/accounts\/\[\^\/\]\+\/archive"\)/)
  assert.deepEqual(Object.keys(archive.responses).sort(), ['200', '400', '401', '403', '404', '409', '422', '500'])
  assert.equal(archive.responses['409'].$ref, '#/components/responses/Conflict')
  assert.equal(archive.responses['422'].$ref, '#/components/responses/NonZeroBalanceConfirmationRequired')
  assert.equal(archive.responses['500'].$ref, '#/components/responses/InternalError')
  assert.equal(archive.requestBody.content['application/json'].schema.$ref, '#/components/schemas/ArchiveAccountRequest')
  assert.deepEqual(archive['x-archive'], {
    authorization: 'OWNER_ONLY',
    balanceSource: 'POSTED_PRIMARY_LEDGER_BALANCE',
    nonZeroBalanceConfirmationField: 'confirmNonZeroBalance',
    nonZeroBalanceFalseCode: 'NON_ZERO_BALANCE_CONFIRMATION_REQUIRED',
    alreadyArchivedCode: 'ACCOUNT_ALREADY_ARCHIVED',
    idempotency: 'USER_API_MAJOR_OPERATION_KEY_REQUEST_HASH',
  })
  assert.equal(archive['x-error-codes'].includes('ACCOUNT_ALREADY_ARCHIVED'), true)
  assert.equal(archive['x-error-codes'].includes('NON_ZERO_BALANCE_CONFIRMATION_REQUIRED'), true)
  assert.equal(archive['x-error-codes'].includes('INTERNAL_ERROR'), true)

  assert.equal(request.additionalProperties, false)
  assert.deepEqual(request.required.sort(), ['reason'])
  assert.equal(request.properties.confirmNonZeroBalance.type, 'boolean')
  assert.equal(request.properties.reason.minLength, 1)
  assert.equal(request.properties.reason.maxLength, 500)

  const parameters = archive.parameters.map((parameter) => parameter.$ref.split('/').at(-1)).sort()
  assert.deepEqual(parameters, ['ArchiveIfMatch', 'IdempotencyKey'])
  assert.equal(contract.components.parameters.IdempotencyKey.required, true)
  assert.equal(contract.components.parameters.ArchiveIfMatch.required, true)
  assert.equal(contract.components.parameters.ArchiveIfMatch['x-runtime-pattern'], '^"[1-9][0-9]*"$')
  assert.equal(contract.components.parameters.ArchiveIfMatch.schema.minLength, 3)
  assert.equal(contract.components.parameters.ArchiveIfMatch.schema.maxLength, 80)

  assert.equal(nonZeroSchema.allOf[1].properties.status.const, 422)
  assert.equal(nonZeroSchema.allOf[1].properties.code.const, 'NON_ZERO_BALANCE_CONFIRMATION_REQUIRED')
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('账户归档确认与生命周期契约静态检查通过。')
