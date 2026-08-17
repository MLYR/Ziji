import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ziji-account-creation-contract-'))
const bundledFile = join(temporaryDirectory, 'openapi.json')

try {
  // 使用 Redocly 结构化读取契约，避免检查结果受 YAML 行序或缩进变化影响。
  execFileSync(pnpm, ['exec', 'redocly', 'bundle', 'openapi/ziji-v1.yaml', '--output', bundledFile, '--ext', 'json'], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const contract = JSON.parse(readFileSync(bundledFile, 'utf8'))
  const schemas = contract.components.schemas
  const createAccount = contract.paths['/accounts'].post
  const postTransaction = contract.paths['/transactions'].post
  const createAccountRequest = schemas.CreateAccountRequest
  const openingBalance = schemas.AccountOpeningBalance
  const postTransactionRequest = schemas.PostTransactionRequest
  const positiveOpeningAmount = schemas.PositiveMoney

  assert.equal(createAccount.operationId, 'createAccount')
  assert.equal(postTransaction.operationId, 'postTransaction')
  assert.equal(createAccount.responses['400'].$ref, '#/components/responses/BadRequest')
  assert.equal('creditLimit' in createAccountRequest.properties, false, 'V1 CreateAccountRequest 不得接收无持久化落点的 creditLimit')
  assert.equal('id' in createAccountRequest.properties, false, 'V1 账户 ID 必须由服务端生成，CreateAccountRequest 不得接收客户端 id')
  assert.equal(createAccountRequest.additionalProperties, false)
  // `id` 不在本体或 class/type 分支属性中且禁止额外字段，提交它必须走 400 VALIDATION_ERROR，不能被 HTTP 层静默忽略。
  const idIsRejectedAsAdditionalProperty = !('id' in createAccountRequest.properties)
    && createAccountRequest.allOf.every((item) => item.oneOf.every((branch) => !('id' in branch.properties)))
    && createAccountRequest.additionalProperties === false
  assert.equal(idIsRejectedAsAdditionalProperty, true, '提交 CreateAccountRequest.id 必须因额外字段被拒绝')
  assert.equal(openingBalance.additionalProperties, false)
  assert.equal(openingBalance.properties.amount.$ref, '#/components/schemas/PositiveMoney')
  assert.equal('oneOf' in positiveOpeningAmount, false, '期初余额金额不得复用重叠 oneOf schema')
  const positiveOpeningAmountPattern = new RegExp(positiveOpeningAmount.pattern)
  // 期初余额只接受单一正金额 pattern，防止整数同时命中两个 oneOf 分支后被误拒绝。
  for (const amount of ['100', '100.00', '0.01']) assert.equal(positiveOpeningAmountPattern.test(amount), true, `应接受期初余额金额：${amount}`)
  for (const amount of ['0', '-1', '1.001', 'not-a-number']) assert.equal(positiveOpeningAmountPattern.test(amount), false, `应拒绝期初余额金额：${amount}`)
  assert.deepEqual(createAccountRequest.properties.openingBalance.oneOf.map((item) => item.$ref ?? item.type).sort(), ['#/components/schemas/AccountOpeningBalance', 'null'].sort())

  const classTypeBranches = createAccountRequest.allOf[0].oneOf.map((branch) => ({
    accountClass: branch.properties.accountClass.const,
    accountTypes: branch.properties.accountType.enum,
  }))
  assert.deepEqual(classTypeBranches, [
    { accountClass: 'ASSET', accountTypes: ['BANK', 'WECHAT', 'ALIPAY', 'CASH', 'OTHER'] },
    { accountClass: 'INVESTMENT', accountTypes: ['BROKERAGE', 'FUND', 'OTHER'] },
    { accountClass: 'LIABILITY', accountTypes: ['CREDIT_CARD', 'LOAN', 'CONSUMER_LOAN', 'OTHER'] },
  ])
  assert.equal(createAccount['x-opening-balance'].scope, 'CREATE_ACCOUNT_ONLY')
  assert.equal(createAccount['x-opening-balance'].internalTransactionType, 'OPENING')
  assert.deepEqual(createAccount['x-opening-balance'].postings, {
    ASSET: 'DEBIT_PRIMARY_CREDIT_EQUITY_OPENING_BALANCE',
    INVESTMENT: 'DEBIT_PRIMARY_CREDIT_EQUITY_OPENING_BALANCE_NO_POSITION_COST',
    LIABILITY: 'DEBIT_EQUITY_OPENING_BALANCE_CREDIT_PRIMARY',
  })

  assert.equal(postTransaction.requestBody.content['application/json'].schema.$ref, '#/components/schemas/PostTransactionRequest')
  const transactionRequestRefs = postTransactionRequest.oneOf.map((item) => item.$ref?.split('/').at(-1))
  assert.equal(transactionRequestRefs.includes('OpeningTransactionRequest'), false, '公共 postTransaction 不得接受 OPENING')
  assert.equal('OPENING' in postTransactionRequest.discriminator.mapping, false, '公共 discriminator 不得暴露 OPENING')
  assert.equal(schemas.OpeningTransactionRequest, undefined, '删除公共 OPENING 后不得保留无引用 schema')
  assert.equal(schemas.TransactionCommandBase.properties.type.enum.includes('OPENING'), false)
  assert.equal(schemas.ReviseTransactionRequest.properties.replacement.$ref, '#/components/schemas/PostTransactionRequest')
  assert.equal(schemas.TransactionTemplate.properties.command.$ref, '#/components/schemas/PostTransactionRequest')
  assert.equal(schemas.LiabilityRepaymentTransactionRequest['x-domain-transaction-type'], 'REPAYMENT')
  assert.equal(postTransactionRequest.discriminator.mapping.LIABILITY_REPAYMENT, '#/components/schemas/LiabilityRepaymentTransactionRequest')

  const openingTransactionId = schemas.AccountCreatedEnvelope.properties.data.properties.openingTransactionId
  assert.deepEqual(openingTransactionId.type.sort(), ['null', 'string'])
  assert.equal(openingTransactionId.format, 'uuid')
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}

console.log('账户创建期初余额契约静态检查通过。')
