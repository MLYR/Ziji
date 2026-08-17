import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-liability-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')

try {
  // 结构化读取最终 bundle，避免测试依赖 YAML 排版，也不为实例检查引入新依赖。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const path = contract.paths['/accounts/{accountId}/liability-details']
  const schemas = contract.components.schemas
  const parameterNames = (operation) => operation.parameters.map((parameter) => parameter.$ref.split('/').at(-1)).sort()

  assert.equal(path.get.operationId, 'getLiabilityDetails')
  assert.equal(path.put.operationId, 'putLiabilityDetails')
  assert.equal(path.patch.operationId, 'patchLiabilityDetails')
  assert.equal(path.delete, undefined, 'V1 不得提供负债详情 DELETE')
  assert.deepEqual(path['x-account-type-field-matrix'], {
    CREDIT_CARD: ['interestRate', 'billingDay', 'repaymentDay', 'currentAmountDue'],
    LOAN: ['interestRate', 'loanDate', 'dueDate', 'repaymentDay', 'currentAmountDue'],
    CONSUMER_LOAN: ['interestRate', 'loanDate', 'dueDate', 'repaymentDay', 'currentAmountDue'],
    OTHER: ['interestRate', 'loanDate', 'dueDate', 'billingDay', 'repaymentDay', 'currentAmountDue'],
  })
  assert.deepEqual(Object.keys(path.get.responses).sort(), ['200', '400', '401', '403', '404'])
  assert.deepEqual(Object.keys(path.put.responses).sort(), ['200', '201', '400', '401', '403', '404', '409', '422'])
  assert.deepEqual(Object.keys(path.patch.responses).sort(), ['200', '400', '401', '403', '404', '409', '422'])
  assert.deepEqual(parameterNames(path.put), ['IdempotencyKey', 'LiabilityDetailIfNoneMatch', 'LiabilityDetailPutIfMatch'].sort())
  assert.deepEqual(parameterNames(path.patch), ['IdempotencyKey', 'LiabilityDetailIfMatch'].sort())
  assert.equal(path.put['x-idempotency'].sameKeyDifferentHash, 'IDEMPOTENCY_KEY_REUSED')
  assert.equal(path.patch['x-idempotency'].sameKeyDifferentHash, 'IDEMPOTENCY_KEY_REUSED')
  assert.equal(path.put['x-idempotency'].sameKeySameHash, 'REPLAY_FIRST_RESPONSE')
  assert.equal(path.patch['x-idempotency'].sameKeySameHash, 'REPLAY_FIRST_RESPONSE')
  assert.equal(path.put['x-account-version-effect'], 'UNCHANGED')
  assert.equal(path.patch['x-account-version-effect'], 'UNCHANGED')
  assert.match(path.put.description, /If-None-Match:\*/)
  assert.match(path.put.description, /If-Match/)
  assert.match(path.patch.description, /尚无持久详情行时返回 404/)
  assert.deepEqual(path.put['x-precondition-order'], [
    'AUTHENTICATION', 'ACCOUNT_VISIBILITY_AND_LIABILITY_TYPE', 'WRITE_PERMISSION', 'HEADER_FORMAT',
    'BUSINESS_RULES', 'SAFE_IDEMPOTENCY_REPLAY', 'PERSISTED_VERSION_OR_ABSENCE', 'IDEMPOTENCY_ACQUIRE',
  ])
  for (const operation of [path.get, path.put, path.patch]) {
    for (const code of Object.keys(operation.responses).filter((code) => !['200', '201'].includes(code))) {
      assert.match(operation.responses[code].$ref, /^#\/components\/responses\//, `${operation.operationId} ${code} 必须引用 Problem response`)
    }
  }

  const ifMatch = contract.components.parameters.LiabilityDetailIfMatch
  const putIfMatch = contract.components.parameters.LiabilityDetailPutIfMatch
  const ifNoneMatch = contract.components.parameters.LiabilityDetailIfNoneMatch
  assert.equal(ifMatch.required, true)
  assert.equal(putIfMatch.required, false)
  assert.equal(ifNoneMatch.required, false)
  assert.equal(ifNoneMatch.schema.const, '*')
  const strongPositiveEtag = new RegExp(ifMatch.schema.pattern)
  for (const value of ['"1"', '"99"']) assert.equal(strongPositiveEtag.test(value), true)
  for (const value of ['"0"', 'W/"1"', '*', '1', '"-1"', '"x"']) assert.equal(strongPositiveEtag.test(value), false)

  const businessFields = ['interestRate', 'loanDate', 'dueDate', 'billingDay', 'repaymentDay', 'currentAmountDue']
  assert.equal(schemas.LiabilityDetail.additionalProperties, false)
  assert.deepEqual(schemas.LiabilityDetail.required.sort(), ['accountId', ...businessFields, 'version'].sort())
  assert.equal(schemas.LiabilityDetail.properties.version.minimum, 0)
  assert.equal(schemas.PutLiabilityDetailRequest.additionalProperties, false)
  assert.deepEqual(schemas.PutLiabilityDetailRequest.required.sort(), businessFields.sort())
  assert.equal(schemas.PatchLiabilityDetailRequest.additionalProperties, false)
  assert.equal(schemas.PatchLiabilityDetailRequest.minProperties, 1)
  assert.equal(schemas.PutLiabilityDetailRequest.properties.currentAmountDue.oneOf[1].$ref, '#/components/schemas/NonNegativePostedMoney')
  assert.equal(schemas.PatchLiabilityDetailRequest.properties.currentAmountDue.oneOf[1].$ref, '#/components/schemas/NonNegativePostedMoney')
  assert.equal(schemas.PutLiabilityDetailRequest.properties.currentAmountDue['x-currency-precision'], 'ACCOUNT_CURRENCY_AT_APPLICATION_BOUNDARY')
  assert.equal(schemas.PatchLiabilityDetailRequest.properties.currentAmountDue['x-currency-precision'], 'ACCOUNT_CURRENCY_AT_APPLICATION_BOUNDARY')

  const interestRatePattern = new RegExp(schemas.PutLiabilityDetailRequest.properties.interestRate.pattern)
  const matchesInterestRate = (value) => typeof value === 'string' && interestRatePattern.test(value)
  for (const value of ['0', '0.045', '1', '1.00000000']) assert.equal(matchesInterestRate(value), true, `应接受利率：${value}`)
  for (const value of ['-0.1', '1.1', '0.123456789', '4.5', 'not-a-number', 0.045]) assert.equal(matchesInterestRate(value), false, `应拒绝利率：${value}`)

  const liabilityDetailEtagPattern = new RegExp(contract.components.responses.LiabilityDetailOk.headers.ETag.schema.pattern)
  assert.equal(liabilityDetailEtagPattern.test('"0"'), true, '虚拟空详情必须有稳定强 ETag "0"')
  assert.equal(liabilityDetailEtagPattern.test('"1"'), true)
  assert.equal(liabilityDetailEtagPattern.test('W/"1"'), false)
  assert.equal(contract.components.responses.LiabilityDetailOk.content['application/json'].schema.$ref, '#/components/schemas/LiabilityDetailEnvelope')
  assert.equal(schemas.LiabilityDetailEnvelope.properties.data.$ref, '#/components/schemas/LiabilityDetail')

  assert.equal('liabilityDetails' in schemas.Account.properties, false, '方案 A 不得把负债详情嵌入 Account')
  assert.equal('liabilityDetails' in schemas.CreateAccountRequest.properties, false, '方案 A 不得扩展 POST /accounts')
  assert.equal('liabilityDetails' in schemas.UpdateAccountRequest.properties, false, '方案 A 不得扩展 Account PATCH')

  const postTransaction = schemas.PostTransactionRequest
  const transactionRefs = postTransaction.oneOf.map((item) => item.$ref.split('/').at(-1))
  assert.equal(transactionRefs.includes('LiabilityBorrowingTransactionRequest'), true)
  assert.equal(postTransaction.discriminator.mapping.LIABILITY_BORROWING, '#/components/schemas/LiabilityBorrowingTransactionRequest')
  assert.equal(schemas.TransactionCommandBase.properties.type.enum.includes('LIABILITY_BORROWING'), false)

  // 周期模板必须使用专用三分支联合体，避免公共退款和负债命令沿共享引用泄漏。
  assert.equal(schemas.TransactionTemplate.properties.command.$ref, '#/components/schemas/RecurringTransactionCommand')
  const recurringTransaction = schemas.RecurringTransactionCommand
  assert.deepEqual(
    recurringTransaction.oneOf.map((item) => item.$ref.split('/').at(-1)).sort(),
    ['IncomeTransactionRequest', 'ExpenseTransactionRequest', 'TransferTransactionRequest'].sort(),
  )
  assert.deepEqual(Object.keys(recurringTransaction.discriminator.mapping).sort(), ['INCOME', 'EXPENSE', 'TRANSFER'].sort())
  for (const forbiddenType of ['REFUND', 'LIABILITY_BORROWING', 'LIABILITY_REPAYMENT', 'OPENING']) {
    assert.equal(forbiddenType in recurringTransaction.discriminator.mapping, false)
  }
  for (const schemaName of ['CreateRecurringRuleRequest', 'UpdateRecurringRuleRequest', 'RecurringRule']) {
    assert.equal(schemas[schemaName].properties.transactionTemplate.$ref, '#/components/schemas/TransactionTemplate')
  }

  assert.equal(schemas.LiabilityBorrowingTransactionRequest['x-domain-transaction-type'], 'TRANSFER')
  assert.equal(schemas.LiabilityBorrowingTransactionRequest['x-postings'], 'DEBIT_ASSET_PRIMARY_CREDIT_LIABILITY_PRIMARY')
  assert.match(schemas.LiabilityBorrowingTransactionRequest['x-business-rules'], /MATCH_REQUEST_CURRENCY_AND_ATOMIC_BALANCE/)
  assert.deepEqual(
    schemas.LiabilityBorrowingTransactionRequest.required.sort(),
    ['type', 'businessAt', 'assetAccountId', 'liabilityAccountId', 'currency', 'amount'].sort(),
  )
  for (const forbiddenField of ['ledgerAccountId', 'entries', 'direction', 'debitAccountId', 'creditAccountId']) {
    assert.equal(forbiddenField in schemas.LiabilityBorrowingTransactionRequest.properties, false)
    assert.equal(forbiddenField in schemas.LiabilityRepaymentTransactionRequest.allOf[1].properties, false)
  }
  assert.equal(contract.paths['/transactions'].post.responses['404'].$ref, '#/components/responses/NotFound')
  assert.equal(schemas.ExpenseTransactionRequest['x-liability-command'].accountType, 'CREDIT_CARD')
  assert.equal(schemas.ExpenseTransactionRequest['x-liability-command'].domainTransactionType, 'EXPENSE')
  assert.equal(schemas.LiabilityRepaymentTransactionRequest['x-domain-transaction-type'], 'REPAYMENT')
  assert.match(schemas.LiabilityRepaymentTransactionRequest['x-business-rules'], /CATEGORY_REQUIRED_IFF_POSITIVE/)
  assert.deepEqual(schemas.LiabilityRepaymentTransactionRequest['x-postings'], {
    principal: 'DEBIT_LIABILITY_PRIMARY_CREDIT_ASSET_PRIMARY',
    interest: 'DEBIT_EXPENSE_CATEGORY_CREDIT_ASSET_PRIMARY',
    fee: 'DEBIT_EXPENSE_CATEGORY_CREDIT_ASSET_PRIMARY',
  })
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('负债详情与负债账务命令契约静态检查通过。')
