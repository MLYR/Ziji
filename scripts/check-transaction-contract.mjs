import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-transaction-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')

try {
  // 使用项目已固定的 Redocly 解析器读取 OpenAPI，避免静态测试依赖 YAML 排版或新增运行时依赖。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const listTransactions = contract.paths['/transactions'].get
  const getTransaction = contract.paths['/transactions/{transactionId}'].get
  const schemas = contract.components.schemas

  assert.equal(listTransactions.operationId, 'listTransactions')
  assert.equal(getTransaction.operationId, 'getTransaction')
  assert.match(listTransactions.description, /dateFrom\/dateTo 包含边界/)
  assert.match(listTransactions.description, /ACTIVE membership/)
  assert.match(listTransactions.description, /cursor 绑定当前 userId、API 主版本、全部筛选条件和排序定义/)

  const parameterRefs = listTransactions.parameters.map((parameter) => parameter.$ref?.split('/').at(-1) ?? parameter.name)
  assert.deepEqual(parameterRefs.sort(), [
    'AccountIdQuery', 'TransactionTypeQuery', 'DateFrom', 'DateTo', 'TransactionCategoryIdQuery', 'Limit', 'TransactionCursor',
  ].sort(), 'listTransactions 参数键集合不符合冻结契约')
  assert.deepEqual(Object.keys(listTransactions.responses).sort(), ['200', '400', '401', '403', '404'], 'listTransactions 响应集合不符合冻结契约')
  assert.deepEqual(Object.keys(getTransaction.responses).sort(), ['200', '400', '401', '403', '404'], 'getTransaction 响应集合不符合冻结契约')

  const ledgerEntryFields = ['id', 'ledgerAccountId', 'sequenceNo', 'direction', 'amount', 'currency', 'businessDate']
  const transactionFields = ['id', 'type', 'status', 'businessAt', 'businessDate', 'timezone', 'source', 'rootTransactionId', 'previousVersionId', 'reversalOfId', 'versionNo', 'version', 'entries']
  assert.deepEqual(Object.keys(schemas.LedgerEntry.properties).sort(), ledgerEntryFields.sort(), 'LedgerEntry 字段不符合冻结契约')
  assert.deepEqual(schemas.LedgerEntry.required.sort(), ledgerEntryFields.sort(), 'LedgerEntry required 不符合冻结契约')
  assert.deepEqual(Object.keys(schemas.Transaction.properties).sort(), transactionFields.sort(), 'Transaction 字段不符合冻结契约')
  assert.deepEqual(schemas.Transaction.required.sort(), ['id', 'type', 'status', 'businessAt', 'businessDate', 'timezone', 'source', 'rootTransactionId', 'versionNo', 'version', 'entries'].sort(), 'Transaction required 不符合冻结契约')
  assert.equal(schemas.Transaction.properties.type.type, 'string')
  assert.equal('enum' in schemas.Transaction.properties.type, false, 'Transaction.type 不应在本任务全局收紧枚举')

  assert.equal(schemas.TransactionEnvelope.properties.data.$ref, '#/components/schemas/Transaction')
  assert.equal(schemas.TransactionEnvelope.properties.meta.$ref, '#/components/schemas/ResponseMeta')
  assert.equal(schemas.TransactionListEnvelope.properties.data.items.$ref, '#/components/schemas/Transaction')
  assert.equal(schemas.TransactionListEnvelope.properties.meta.$ref, '#/components/schemas/TransactionPageMeta')
  assert.deepEqual(schemas.PageMeta.required.sort(), ['requestId', 'hasMore'].sort())
  assert.deepEqual(schemas.TransactionPageMeta.required.sort(), ['requestId', 'nextCursor', 'hasMore'].sort())

  for (const responseName of ['BadRequest', 'Unauthenticated', 'Forbidden', 'NotFound']) {
    assert.equal(
      contract.components.responses[responseName].content['application/problem+json'].schema.$ref,
      '#/components/schemas/Problem',
      `${responseName} 必须引用 Problem schema`,
    )
  }
  assert.match(contract.components.responses.TransactionOk.headers.ETag.schema.pattern, /^\^?"/)
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('交易读取契约静态检查通过。')
