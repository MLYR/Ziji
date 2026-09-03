import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertCircleIcon, CheckCircle2Icon, LoaderCircleIcon } from 'lucide-react'
import { Fragment, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import type { components } from '@ziji/api-types'

import { useWebAuth } from '@/auth/auth-session'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { describeProblem } from '@/lib/problem-messages'
import {
  createBalanceAdjustment,
  createTransaction,
  listAccounts,
  type Account,
} from '@/ledger/transaction-api'
import { listCategories, listTags, type Tag } from '@/categories/categories-api'

type TransactionType = 'EXPENSE' | 'INCOME' | 'REFUND' | 'TRANSFER' | 'LIABILITY_REPAYMENT' | 'ADJUSTMENT'

type MoneyAmount = components['schemas']['MoneyAmount']

/** 账户币种来自服务端 Account.currency，金额已按该币种精度格式化；生成类型把金额建模为逐币种联合。 */
function moneyAmount(amount: string, currency: string): MoneyAmount {
  return { amount, currency } as MoneyAmount
}

const TYPE_OPTIONS: { value: TransactionType; label: string; hint: string }[] = [
  { value: 'EXPENSE', label: '支出', hint: '从资产账户付出，或使用信用卡消费。' },
  { value: 'INCOME', label: '收入', hint: '资金进入资产账户，计入收入统计。' },
  { value: 'REFUND', label: '退款', hint: '关联原支出交易的退款到账。' },
  { value: 'TRANSFER', label: '转账', hint: '同币种账户间转账，可记录手续费。' },
  { value: 'LIABILITY_REPAYMENT', label: '负债还款', hint: '本金不计支出；利息和手续费分别使用费用分类。' },
  { value: 'ADJUSTMENT', label: '余额调整', hint: '以实际余额为准的对账修正。' },
]

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2
}

function amountPattern(currency: string): RegExp {
  const decimals = currencyMinorUnits(currency)
  return decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`)
}

function nowLocalInput(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60000
  return new Date(now.getTime() - offset).toISOString().slice(0, 16)
}

function userTimezone(userTimezone: string | undefined): string {
  return userTimezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone
}

function toInstant(localValue: string): string {
  // datetime-local 值按浏览器本地时区解析；服务端再用时区字段固化业务日期。
  return new Date(localValue).toISOString()
}

function formatAmount(value: string, currency: string): string {
  const [integer, fraction = ''] = value.trim().split('.')
  const decimals = currencyMinorUnits(currency)
  // 金额字符串不经二进制浮点数，避免在客户端格式化时丢失大金额的低位精度。
  return decimals === 0 ? integer : `${integer}.${fraction.padEnd(decimals, '0')}`
}

function isPositiveAmount(value: string): boolean {
  return !/^0+(?:\.0+)?$/.test(value.trim())
}

interface FormErrors {
  summary?: string
  fields: Record<string, string>
}

const FIELD_LABELS: Record<string, string> = {
  accountId: '账户',
  fromAccountId: '转出账户',
  toAccountId: '转入账户',
  amount: '金额',
  originalTransactionId: '原交易 ID',
  fee: '手续费',
  feeCategoryId: '手续费分类',
  cashAccountId: '付款账户',
  liabilityAccountId: '负债账户',
  principalAmount: '本金',
  interestAmount: '利息',
  interestCategoryId: '利息分类',
  feeAmount: '手续费',
  actualBalance: '调整后余额',
  reason: '调整原因',
  categoryId: '分类',
}

function fieldLabel(key: string): string {
  return FIELD_LABELS[key] ?? key
}

export function RecordTransactionPage() {
  const { user } = useWebAuth()
  const queryClient = useQueryClient()
  const [type, setType] = useState<TransactionType>('EXPENSE')
  const [accountId, setAccountId] = useState('')
  const [amount, setAmount] = useState('')
  const [merchant, setMerchant] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [note, setNote] = useState('')
  const [originalTransactionId, setOriginalTransactionId] = useState('')
  const [fromAccountId, setFromAccountId] = useState('')
  const [toAccountId, setToAccountId] = useState('')
  const [fromAmount, setFromAmount] = useState('')
  const [fee, setFee] = useState('')
  const [feeCategoryId, setFeeCategoryId] = useState('')
  const [cashAccountId, setCashAccountId] = useState('')
  const [liabilityAccountId, setLiabilityAccountId] = useState('')
  const [principalAmount, setPrincipalAmount] = useState('')
  const [interestAmount, setInterestAmount] = useState('0')
  const [interestCategoryId, setInterestCategoryId] = useState('')
  const [feeAmount, setFeeAmount] = useState('0')
  const [repaymentFeeCategoryId, setRepaymentFeeCategoryId] = useState('')
  const [tagIds, setTagIds] = useState<string[]>([])
  const [actualBalance, setActualBalance] = useState('')
  const [reason, setReason] = useState('')
  const [businessAt, setBusinessAt] = useState(nowLocalInput)
  const [errors, setErrors] = useState<FormErrors>({ fields: {} })
  const [createdId, setCreatedId] = useState<string | null>(null)

  const accountsQuery = useQuery({
    queryKey: ['accounts', 'for-record'],
    queryFn: listAccounts,
    staleTime: 60_000,
  })
  const categoriesQuery = useQuery({ queryKey: ['categories', 'PERSONAL'], queryFn: () => listCategories('PERSONAL') })
  const tagsQuery = useQuery({ queryKey: ['tags'], queryFn: listTags })
  const accounts = accountsQuery.data?.accounts ?? []
  const activeAccounts = accounts.filter((account) => account.status === 'ACTIVE')
  const accountById = useMemo(() => {
    const index = new Map<string, Account>()
    for (const account of activeAccounts) index.set(account.id, account)
    return index
  }, [activeAccounts])

  const selectedAccount = accountById.get(accountId)
  const cashAccount = accountById.get(cashAccountId)
  const selectedCurrency = (type === 'LIABILITY_REPAYMENT' ? cashAccount?.currency : selectedAccount?.currency) ?? user?.baseCurrency ?? 'CNY'
  const fromAccount = accountById.get(fromAccountId)
  const toAccount = accountById.get(toAccountId)
  const liabilityAccount = accountById.get(liabilityAccountId)

  const payload = useMemo(() => JSON.stringify({
    type, accountId, amount, merchant, categoryId, note, originalTransactionId,
    fromAccountId, toAccountId, fromAmount, fee, feeCategoryId,
    cashAccountId, liabilityAccountId, principalAmount, interestAmount, interestCategoryId, feeAmount, repaymentFeeCategoryId,
    actualBalance, reason, businessAt,
  }), [type, accountId, amount, merchant, categoryId, note, originalTransactionId,
    fromAccountId, toAccountId, fromAmount, fee, feeCategoryId,
    cashAccountId, liabilityAccountId, principalAmount, interestAmount, interestCategoryId, feeAmount, repaymentFeeCategoryId,
    actualBalance, reason, businessAt])

  // 幂等键与载荷绑定：内容不变的重试复用同一键，修改内容后立即换新键，避免同键异参冲突。
  // oxlint-disable-next-line react-hooks/exhaustive-deps -- randomUUID 是刻意的副作用键，只随载荷变化重建。
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [payload])

  const mutation = useMutation({
    mutationFn: async () => {
      const timezone = userTimezone(user?.timezone)
      const businessAtInstant = toInstant(businessAt)
      if (type === 'ADJUSTMENT') {
        return createBalanceAdjustment(accountId, idempotencyKey, {
          actualBalance: formatAmount(actualBalance, selectedCurrency),
          businessAt: businessAtInstant,
          timezone,
          reason,
        })
      }
      const base = {
        businessAt: businessAtInstant,
        timezone,
        note: note.trim() === '' ? null : note.trim(),
        tagIds: tagIds.length > 0 ? tagIds : undefined,
      }
      if (type === 'EXPENSE') {
        return createTransaction(idempotencyKey, {
          type: 'EXPENSE',
          accountId,
          amount: formatAmount(amount, selectedCurrency),
          currency: selectedCurrency,
          categoryId,
          merchant: merchant.trim() === '' ? null : merchant.trim(),
          businessAt: base.businessAt,
          timezone: base.timezone,
          note: base.note,
        })
      }
      if (type === 'INCOME') {
        return createTransaction(idempotencyKey, {
          type: 'INCOME',
          accountId,
          amount: formatAmount(amount, selectedCurrency),
          currency: selectedCurrency,
          categoryId,
          businessAt: base.businessAt,
          timezone: base.timezone,
          note: base.note,
        })
      }
      if (type === 'REFUND') {
        return createTransaction(idempotencyKey, {
          type: 'REFUND',
          accountId,
          amount: formatAmount(amount, selectedCurrency),
          currency: selectedCurrency,
          originalTransactionId,
          businessAt: base.businessAt,
          timezone: base.timezone,
          note: base.note,
        })
      }
      if (type === 'LIABILITY_REPAYMENT') {
        return createTransaction(idempotencyKey, {
          type: 'LIABILITY_REPAYMENT',
          cashAccountId,
          liabilityAccountId,
          currency: selectedCurrency,
          principalAmount: formatAmount(principalAmount, selectedCurrency),
          interestAmount: formatAmount(interestAmount.trim() === '' ? '0' : interestAmount, selectedCurrency),
          feeAmount: formatAmount(feeAmount.trim() === '' ? '0' : feeAmount, selectedCurrency),
          interestCategoryId: interestAmount.trim() === '' || !isPositiveAmount(interestAmount) ? null : interestCategoryId,
          feeCategoryId: feeAmount.trim() === '' || !isPositiveAmount(feeAmount) ? null : repaymentFeeCategoryId,
          businessAt: base.businessAt,
          timezone: base.timezone,
          note: base.note,
        })
      }
      return createTransaction(idempotencyKey, {
        type: 'TRANSFER',
        fromAccountId,
        toAccountId,
        fromAmount: moneyAmount(formatAmount(fromAmount, selectedCurrency), selectedCurrency),
        toAmount: moneyAmount(formatAmount(fromAmount, selectedCurrency), selectedCurrency),
        fee: moneyAmount(fee.trim() === '' ? '0.00' : formatAmount(fee, selectedCurrency), selectedCurrency),
        feeCategoryId: fee.trim() === '' ? null : feeCategoryId,
        businessAt: base.businessAt,
        timezone: base.timezone,
        note: base.note,
      })
    },
    onSuccess: (transactionId) => {
      setCreatedId(transactionId)
      setErrors({ fields: {} })
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
    onError: () => {
      setCreatedId(null)
    },
  })

  function validate(): FormErrors {
    const fields: Record<string, string> = {}
    const currency = selectedCurrency
    const checkAmount = (key: string, value: string) => {
      if (value.trim() === '') fields[key] = `${fieldLabel(key)}不能为空`
      else if (!amountPattern(currency).test(value.trim())) fields[key] = `金额格式需符合 ${currency} 记账精度`
    }
    const checkNonNegativeAmount = (key: string, value: string) => {
      if (value.trim() !== '' && !amountPattern(currency).test(value.trim())) fields[key] = `金额格式需符合 ${currency} 记账精度`
    }
    const checkAccount = (key: string, value: string) => {
      if (value === '') fields[key] = `${fieldLabel(key)}不能为空`
      else if (!accountById.has(value)) fields[key] = `${fieldLabel(key)}不存在或不可用`
    }
    if (type === 'ADJUSTMENT') {
      checkAccount('accountId', accountId)
      checkAmount('actualBalance', actualBalance)
      if (reason.trim() === '') fields.reason = '调整原因不能为空'
    } else {
      if (type === 'TRANSFER') {
        checkAccount('fromAccountId', fromAccountId)
        checkAccount('toAccountId', toAccountId)
        if (fromAccountId !== '' && fromAccountId === toAccountId) fields.toAccountId = '转入账户不能与转出账户相同'
        checkAmount('amount', fromAmount)
        if (fromAccount && toAccount && fromAccount.currency !== toAccount.currency) {
          fields.toAccountId = 'B1 转账仅支持同币种账户'
        }
        if (fee.trim() !== '') {
          checkAmount('fee', fee)
          if (isPositiveAmount(fee) && feeCategoryId === '') fields.feeCategoryId = '手续费大于 0 时必须选择手续费分类'
          if (!isPositiveAmount(fee) && feeCategoryId !== '') fields.feeCategoryId = '手续费为 0 时不能填写分类'
        } else if (feeCategoryId !== '') {
          fields.feeCategoryId = '手续费为 0 时不能填写分类'
        }
      } else if (type === 'LIABILITY_REPAYMENT') {
        checkAccount('cashAccountId', cashAccountId)
        checkAccount('liabilityAccountId', liabilityAccountId)
        if (cashAccountId !== '' && cashAccount?.accountClass !== 'ASSET') fields.cashAccountId = '付款账户必须是资产账户'
        if (liabilityAccountId !== '' && liabilityAccount?.accountClass !== 'LIABILITY') fields.liabilityAccountId = '负债账户必须是负债账户'
        if (cashAccount && liabilityAccount && cashAccount.currency !== liabilityAccount.currency) fields.liabilityAccountId = '还款仅支持同币种资产与负债账户'
        checkAmount('principalAmount', principalAmount)
        if (principalAmount.trim() !== '' && isPositiveAmount(principalAmount) === false) fields.principalAmount = '本金必须大于 0'
        checkNonNegativeAmount('interestAmount', interestAmount)
        checkNonNegativeAmount('feeAmount', feeAmount)
        if (interestAmount.trim() !== '' && isPositiveAmount(interestAmount)) {
          if (interestCategoryId === '') fields.interestCategoryId = '利息大于 0 时必须选择利息分类'
        } else if (interestCategoryId !== '') {
          fields.interestCategoryId = '利息为 0 时不能填写分类'
        }
        if (feeAmount.trim() !== '' && isPositiveAmount(feeAmount)) {
          if (repaymentFeeCategoryId === '') fields.feeCategoryId = '手续费大于 0 时必须选择手续费分类'
        } else if (repaymentFeeCategoryId !== '') {
          fields.feeCategoryId = '手续费为 0 时不能填写分类'
        }
      } else {
        checkAccount('accountId', accountId)
        checkAmount('amount', amount)
        // 分类只来自服务端查询结果，客户端只需校验必填。
        if (categoryId === '') fields.categoryId = '请选择分类'
      }
      if (type === 'REFUND' && !UUID_PATTERN.test(originalTransactionId)) {
        fields.originalTransactionId = '请填写原支出交易 ID'
      }
    }
    return { fields, summary: Object.keys(fields).length === 0 ? undefined : '请先修正表单中的问题' }
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const validation = validate()
    setErrors(validation)
    if (validation.summary !== undefined) return
    setCreatedId(null)
    mutation.mutate()
  }

  function resetForm(nextType: TransactionType = type) {
    setType(nextType)
    setAmount('')
    setMerchant('')
    setOriginalTransactionId('')
    setFromAmount('')
    setFee('')
    setFeeCategoryId('')
    setCashAccountId('')
    setLiabilityAccountId('')
    setPrincipalAmount('')
    setInterestAmount('0')
    setInterestCategoryId('')
    setFeeAmount('0')
    setRepaymentFeeCategoryId('')
    setActualBalance('')
    setReason('')
    setErrors({ fields: {} })
    setCreatedId(null)
  }

  const amountHelp = (currency: string) => `${currency} · 最多 ${currencyMinorUnits(currency)} 位小数`

  const accountSelect = (id: string, value: string, onChange: (next: string) => void, label: string, filter?: (account: Account) => boolean, errorKey = id) => (
    <div className="flex flex-col gap-2">
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="h-9 rounded-md border bg-transparent px-3 text-sm"
        aria-invalid={errors.fields[errorKey] ? true : undefined}
      >
        <option value="">请选择账户</option>
        {activeAccounts.filter((account) => filter?.(account) ?? true).map((account) => (
          <option key={account.id} value={account.id}>{account.name} · {account.currency}</option>
        ))}
      </select>
      {errors.fields[errorKey] ? <p className="text-sm text-destructive">{errors.fields[errorKey]}</p> : null}
    </div>
  )

  const amountField = (id: string, errorKey: string, label: string, value: string, onChange: (next: string) => void, currency: string) => (
    <div className="flex flex-col gap-2">
      <label htmlFor={id}>{label}</label>
      <Input id={id} inputMode="decimal" value={value} onChange={(event) => onChange(event.target.value)}
        aria-invalid={errors.fields[errorKey] ? true : undefined} placeholder="0.00" />
      <p className="text-xs text-muted-foreground">{amountHelp(currency)}</p>
      {errors.fields[errorKey] ? <p className="text-sm text-destructive">{errors.fields[errorKey]}</p> : null}
    </div>
  )

  const availableCategories = (categoriesQuery.data ?? []).filter((category) =>
    category.status === 'ACTIVE' && category.parentId === null)
  const childCategories = (categoriesQuery.data ?? []).filter((category) =>
    category.status === 'ACTIVE' && category.parentId !== null)

  const categorySelect = (id: string, errorKey: string, label: string, value: string, onChange: (next: string) => void, categoryType: 'EXPENSE' | 'INCOME' = 'EXPENSE') => (
    <div className="flex flex-col gap-2">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(event) => onChange(event.target.value)}
        aria-invalid={errors.fields[errorKey] ? true : undefined}
        className="h-9 rounded-md border bg-transparent px-3 text-sm">
        <option value="">未选择</option>
        {availableCategories.filter((category) => category.categoryType === categoryType).map((category) => (
          <Fragment key={category.id}>
            <option value={category.id}>{category.name}</option>
            {childCategories.filter((child) => child.parentId === category.id && child.categoryType === categoryType).map((child) => (
              <option key={child.id} value={child.id}>{category.name} / {child.name}</option>
            ))}
          </Fragment>
        ))}
      </select>
      {errors.fields[errorKey] ? <p className="text-sm text-destructive">{errors.fields[errorKey]}</p> : null}
    </div>
  )

  const noteField = (
    <div className="flex flex-col gap-2 md:col-span-2">
      <label htmlFor="record-note">备注</label>
      <Input id="record-note" value={note} onChange={(event) => setNote(event.target.value)} placeholder="可选" />
    </div>
  )

  const businessAtField = (
    <div className="flex flex-col gap-2">
      <label htmlFor="record-business-at">业务时间</label>
      <Input id="record-business-at" type="datetime-local" value={businessAt}
        onChange={(event) => setBusinessAt(event.target.value)} />
      <p className="text-xs text-muted-foreground">业务日期按 {userTimezone(user?.timezone)} 固化。</p>
    </div>
  )

  return (
    <main id="main-content" className="mx-auto flex w-full max-w-3xl flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-col gap-1">
        <Badge variant="outline">记一笔</Badge>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">按资金动作记录</h1>
        <p className="text-sm text-muted-foreground">选择符合实际资金动作的类型，无需理解会计分录。</p>
      </section>

      <Card>
        <CardHeader>
          <CardTitle>交易类型</CardTitle>
          <CardDescription>{TYPE_OPTIONS.find((option) => option.value === type)?.hint}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <div role="tablist" aria-label="交易类型" className="flex flex-wrap gap-2">
            {TYPE_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                role="tab"
                aria-selected={type === option.value}
                onClick={() => resetForm(option.value)}
                className={`rounded-md border px-3 py-1.5 text-sm transition-colors ${type === option.value ? 'border-primary bg-primary text-primary-foreground' : 'bg-background hover:bg-muted'}`}
              >
                {option.label}
              </button>
            ))}
          </div>

          {createdId ? (
            <div className="flex flex-col gap-3 rounded-md border border-green-600/40 bg-green-500/10 p-4" role="status">
              <div className="flex items-center gap-2 font-medium text-green-700 dark:text-green-400">
                <CheckCircle2Icon className="size-4" />交易已保存
              </div>
              <p className="text-sm text-muted-foreground">交易 ID：{createdId}</p>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => resetForm()}>再记一笔</Button>
                <Button variant="ghost" asChild><Link to="/transactions">查看流水</Link></Button>
              </div>
            </div>
          ) : null}

          {mutation.isError ? (
            <Alert data-testid="submit-error">
              <AlertCircleIcon />
              <p>{describeProblem(mutation.error)}</p>
            </Alert>
          ) : null}

          <form className="grid gap-4 md:grid-cols-2" onSubmit={handleSubmit} noValidate>
            {type === 'ADJUSTMENT' ? (
              <>
                {accountSelect('adjust-account', accountId, setAccountId, '调整账户', undefined, 'accountId')}
                {amountField('record-actual-balance', 'actualBalance', '调整后余额', actualBalance, setActualBalance, selectedCurrency)}
                {businessAtField}
                <div className="flex flex-col gap-2">
                  <label htmlFor="record-reason">调整原因</label>
                  <Input id="record-reason" value={reason} onChange={(event) => setReason(event.target.value)}
                    aria-invalid={errors.fields.reason ? true : undefined} placeholder="例如：对账修正" />
                  {errors.fields.reason ? <p className="text-sm text-destructive">{errors.fields.reason}</p> : null}
                </div>
              </>
            ) : type === 'TRANSFER' ? (
              <>
                {accountSelect('record-from-account', fromAccountId, setFromAccountId, '转出账户')}
                {accountSelect('record-to-account', toAccountId, setToAccountId, '转入账户')}
                {amountField('record-from-amount', 'amount', '转出金额', fromAmount, setFromAmount, fromAccount?.currency ?? selectedCurrency)}
                {amountField('record-fee', 'fee', '手续费（可选）', fee, setFee, selectedCurrency)}
                {fee.trim() !== '' ? categorySelect('record-fee-category', 'feeCategoryId', '手续费分类', feeCategoryId, setFeeCategoryId) : null}
                {businessAtField}
                {noteField}
              </>
            ) : type === 'LIABILITY_REPAYMENT' ? (
              <>
                {accountSelect('repayment-cash-account', cashAccountId, setCashAccountId, '付款账户', (account) => account.accountClass === 'ASSET', 'cashAccountId')}
                {accountSelect('repayment-liability-account', liabilityAccountId, setLiabilityAccountId, '负债账户', (account) => account.accountClass === 'LIABILITY', 'liabilityAccountId')}
                {amountField('repayment-principal-amount', 'principalAmount', '本金', principalAmount, setPrincipalAmount, selectedCurrency)}
                {amountField('repayment-interest-amount', 'interestAmount', '利息', interestAmount, setInterestAmount, selectedCurrency)}
                {interestAmount.trim() !== '' && isPositiveAmount(interestAmount)
                  ? categorySelect('repayment-interest-category', 'interestCategoryId', '利息分类', interestCategoryId, setInterestCategoryId)
                  : null}
                {amountField('repayment-fee-amount', 'feeAmount', '手续费', feeAmount, setFeeAmount, selectedCurrency)}
                {feeAmount.trim() !== '' && isPositiveAmount(feeAmount)
                  ? categorySelect('repayment-fee-category', 'feeCategoryId', '手续费分类', repaymentFeeCategoryId, setRepaymentFeeCategoryId)
                  : null}
                {businessAtField}
                {noteField}
                <p className="text-xs text-muted-foreground md:col-span-2">本金只减少资产和负债，不计入支出；利息和手续费会分别计入所选费用分类。</p>
              </>
            ) : (
              <>
                {accountSelect('record-account', accountId, setAccountId, type === 'REFUND' ? '退款到账账户' : type === 'EXPENSE' ? '账户（资产或信用卡）' : '账户',
                  (account) => type === 'EXPENSE' ? account.accountClass === 'ASSET' || account.accountType === 'CREDIT_CARD' : account.accountClass === 'ASSET')}
                {amountField('record-amount', 'amount', type === 'INCOME' ? '收入金额' : type === 'REFUND' ? '退款金额' : '支出金额', amount, setAmount, selectedCurrency)}
                {type === 'REFUND'
                  ? (
                    <div className="flex flex-col gap-2">
                      <label htmlFor="record-original">原支出交易 ID</label>
                      <Input id="record-original" value={originalTransactionId} onChange={(event) => setOriginalTransactionId(event.target.value)}
                        aria-invalid={errors.fields.originalTransactionId ? true : undefined} placeholder="原支出交易 ID" />
                      {errors.fields.originalTransactionId ? <p className="text-sm text-destructive">{errors.fields.originalTransactionId}</p> : null}
                    </div>
                  )
                  : categorySelect('record-category', 'categoryId', '分类', categoryId, setCategoryId, type === 'INCOME' ? 'INCOME' : 'EXPENSE')}
                {type === 'EXPENSE' ? (
                  <div className="flex flex-col gap-2">
                    <label htmlFor="record-merchant">商户</label>
                    <Input id="record-merchant" value={merchant} onChange={(event) => setMerchant(event.target.value)} placeholder="可选" />
                  </div>
                ) : null}
                {businessAtField}
                {noteField}
              </>
            )}

            {type !== 'ADJUSTMENT' ? (
              <div className="flex flex-col gap-2 md:col-span-2">
                <span className="text-sm font-medium">标签（可选）</span>
                <div className="flex flex-wrap gap-3">
                  {(tagsQuery.data ?? []).filter((tag: Tag) => tag.status === 'ACTIVE').map((tag) => (
                    <label key={tag.id} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={tagIds.includes(tag.id)}
                        onChange={(event) => setTagIds((previous) =>
                          event.target.checked ? [...previous, tag.id] : previous.filter((id) => id !== tag.id))}
                      />
                      {tag.name}
                    </label>
                  ))}
                  {tagsQuery.data && tagsQuery.data.length === 0 ? (
                    <p className="text-xs text-muted-foreground">暂无标签，可在「分类」管理页创建。</p>
                  ) : null}
                </div>
              </div>
            ) : null}

            <div className="flex items-center gap-3 md:col-span-2">
              <Button type="submit" disabled={mutation.isPending}>
                {mutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                保存交易
              </Button>
              <p className="text-xs text-muted-foreground">提交携带幂等键：网络重试不会产生重复交易。</p>
            </div>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
