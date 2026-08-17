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
  const nonNegativePostedMoney = schemas.NonNegativePostedMoney
  const positivePostedMoney = schemas.PositivePostedMoney

  assert.equal(nonNegativePostedMoney.type, 'string')
  assert.equal(positivePostedMoney.type, 'string')
  assert.equal('oneOf' in nonNegativePostedMoney, false, 'NonNegativePostedMoney 不得用重叠 oneOf')
  assert.equal('oneOf' in positivePostedMoney, false, 'PositivePostedMoney 不得用重叠 oneOf')
  const matchesPostedMoney = (schema, value) => typeof value === 'string' && new RegExp(schema.pattern).test(value)
  for (const value of ['1', '100', '100.00', '0.01', '0001']) {
    assert.equal(matchesPostedMoney(positivePostedMoney, value), true, `PositivePostedMoney 应接受：${value}`)
  }
  for (const value of ['0', '0.00', '-1', '1.001', 'not-a-number', '00.01', 100, null]) {
    assert.equal(matchesPostedMoney(positivePostedMoney, value), false, `PositivePostedMoney 应拒绝：${value}`)
  }
  for (const value of ['0', '0.00', '1', '100.00']) {
    assert.equal(matchesPostedMoney(nonNegativePostedMoney, value), true, `NonNegativePostedMoney 应接受：${value}`)
  }
  for (const value of ['-1', '1.001', 'not-a-number', '00', 100, null]) {
    assert.equal(matchesPostedMoney(nonNegativePostedMoney, value), false, `NonNegativePostedMoney 应拒绝：${value}`)
  }
  // JPY 的 0 位精度仍由携带 currency 的入账边界校验；公共字符串 schema 必须先接受合法整数。
  assert.equal(matchesPostedMoney(positivePostedMoney, '100'), true, 'JPY 合法整数不得被 PositivePostedMoney 拒绝')

  // 统一检查所有公共收入/支出/退款/同步和负债还款调用方仍引用对应的 PostedMoney schema。
  for (const schemaName of [
    'IncomeTransactionRequest', 'ExpenseTransactionRequest', 'RefundTransactionRequest',
    'SyncIncomeTransactionRequest', 'SyncExpenseTransactionRequest', 'SyncRefundTransactionRequest',
  ]) {
    const amount = schemas[schemaName].allOf?.[1]?.properties?.amount ?? schemas[schemaName].properties?.amount
    assert.equal(amount.$ref, '#/components/schemas/PositivePostedMoney', `${schemaName}.amount 引用错误`)
  }
  const liabilityAmounts = schemas.LiabilityRepaymentTransactionRequest.allOf[1].properties
  assert.equal(liabilityAmounts.principalAmount.$ref, '#/components/schemas/PositivePostedMoney')
  assert.equal(liabilityAmounts.interestAmount.$ref, '#/components/schemas/NonNegativePostedMoney')
  assert.equal(liabilityAmounts.feeAmount.$ref, '#/components/schemas/NonNegativePostedMoney')
  assert.equal(schemas.ReviseTransactionRequest.properties.replacement.$ref, '#/components/schemas/PostTransactionRequest')
  assert.equal(schemas.SyncCreateTransactionOperation.properties.payload.$ref, '#/components/schemas/SyncCreateTransactionPayload')
  assert.equal(schemas.SyncUpdateTransactionOperation.properties.payload.$ref, '#/components/schemas/SyncUpdateTransactionPayload')
  assert.equal(schemas.SyncUpdateTransactionPayload.properties.replacement.$ref, '#/components/schemas/SyncCreateTransactionPayload')

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
