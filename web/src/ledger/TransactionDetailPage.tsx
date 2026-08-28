import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircleIcon } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { listAccounts, type Account } from '@/accounts/accounts-api'
import { describeProblem } from '@/lib/problem-messages'
import {
  fetchTransaction,
  reviseTransaction,
  reverseTransaction,
  transactionEtag,
  type PostTransactionBody,
  type Transaction,
  type TransactionType,
} from '@/ledger/transaction-api'

const TYPE_LABELS: Partial<Record<TransactionType, string>> = {
  OPENING: '期初',
  INCOME: '收入',
  EXPENSE: '支出',
  REFUND: '退款',
  TRANSFER: '转账',
  FX_TRANSFER: '跨币种转账',
  ADJUSTMENT: '余额调整',
  INVESTMENT: '投资',
  REPAYMENT: '负债还款',
  INTEREST: '利息',
  REVERSAL: '冲正',
}

const STATUS_LABELS: Record<Transaction['status'], string> = {
  DRAFT: '草稿',
  POSTED: '已入账',
  REVERSED: '已作废',
  SUPERSEDED: '已替代',
  DISCARDED: '已丢弃',
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2
}

function amountPattern(currency: string): RegExp {
  const decimals = currencyMinorUnits(currency)
  return decimals === 0 ? /^\d+/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`)
}

function formatAmount(value: string, currency: string): string {
  const decimals = currencyMinorUnits(currency)
  return decimals === 0 ? value : Number(value).toFixed(decimals)
}

function EntryList({ transaction }: { transaction: Transaction }) {
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {transaction.entries.map((entry) => (
        <li key={entry.id} className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground">#{entry.sequenceNo} · {entry.direction === 'D' ? '借' : '贷'}</span>
          <span className="font-medium">{entry.amount} {entry.currency}</span>
        </li>
      ))}
    </ul>
  )
}

function VersionLink({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  return (
    <div className="flex flex-col gap-0.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="truncate font-mono text-xs">{value}</span>
    </div>
  )
}

export function TransactionDetailPage() {
  const { transactionId } = useParams<{ transactionId: string }>()
  const queryClient = useQueryClient()
  const [revising, setRevising] = useState(false)
  const [reviseAccountId, setReviseAccountId] = useState('')
  const [reviseAmount, setReviseAmount] = useState('')
  const [reviseCategoryId, setReviseCategoryId] = useState('')
  const [reviseReason, setReviseReason] = useState('')
  const [voiding, setVoiding] = useState(false)
  const [voidReason, setVoidReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const transactionQuery = useQuery({
    queryKey: ['transaction', transactionId],
    queryFn: () => fetchTransaction(transactionId!),
    enabled: Boolean(transactionId),
  })
  const transaction = transactionQuery.data
  const revisable = transaction?.status === 'POSTED' && (transaction.type === 'INCOME' || transaction.type === 'EXPENSE')

  const accountsQuery = useQuery({
    queryKey: ['accounts', 'for-revise'],
    queryFn: listAccounts,
    enabled: revisable,
    staleTime: 60_000,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['transaction', transactionId] })
    void queryClient.invalidateQueries({ queryKey: ['transactions'] })
    void queryClient.invalidateQueries({ queryKey: ['accounts'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }

  const reviseMutation = useMutation({
    mutationFn: () => {
      if (!transaction) throw new Error('交易尚未加载')
      if (transaction.type !== 'INCOME' && transaction.type !== 'EXPENSE') throw new Error('当前交易类型不可修改')
      const account = accountsQuery.data?.accounts.find(({ id }) => id === reviseAccountId)
      if (!account) throw new Error('请选择有效账户')
      const replacement = {
        type: transaction.type,
        accountId: account.id,
        amount: formatAmount(reviseAmount.trim(), account.currency),
        currency: account.currency,
        categoryId: reviseCategoryId.trim(),
        businessAt: transaction.businessAt,
        timezone: transaction.timezone,
      } satisfies PostTransactionBody
      return reviseTransaction(transactionId!, transactionEtag(transaction.version), crypto.randomUUID(), {
        reason: reviseReason.trim(),
        replacement,
      })
    },
    onSuccess: () => {
      setRevising(false)
      setError(null)
      invalidate()
    },
    onError: (mutationError) => setError(describeProblem(mutationError)),
  })

  const reverseMutation = useMutation({
    mutationFn: () => {
      if (!transaction) throw new Error('交易尚未加载')
      return reverseTransaction(transactionId!, transactionEtag(transaction.version), crypto.randomUUID(), {
        reason: voidReason.trim(),
      })
    },
    onSuccess: () => {
      setVoiding(false)
      setVoidReason('')
      setError(null)
      invalidate()
    },
    onError: (mutationError) => setError(describeProblem(mutationError)),
  })

  if (transactionQuery.isPending) {
    return (
      <main id="main-content" className="p-6 lg:p-8" aria-busy="true">
        <p className="text-sm text-muted-foreground">正在加载交易…</p>
      </main>
    )
  }

  if (transactionQuery.isError || !transaction) {
    return (
      <main id="main-content" className="p-6 lg:p-8">
        <Card className="mx-auto max-w-md">
          <CardHeader>
            <CardTitle>无法加载交易</CardTitle>
            <CardDescription>交易可能不存在或当前不可见。</CardDescription>
          </CardHeader>
          <CardContent>
            <Button variant="outline" asChild><Link to="/transactions">返回流水</Link></Button>
          </CardContent>
        </Card>
      </main>
    )
  }

  const accounts = accountsQuery.data?.accounts.filter((account) => account.status === 'ACTIVE') ?? []
  const selectedAccount: Account | undefined = accounts.find(({ id }) => id === reviseAccountId)
  const reviseCurrency = selectedAccount?.currency ?? transaction.entries[0]?.currency ?? 'CNY'

  function submitRevise(event: React.FormEvent) {
    event.preventDefault()
    if (!transaction || !selectedAccount) {
      setError(reviseAccountId === '' ? '请选择账户' : '账户不存在或不可用')
      return
    }
    const problems: string[] = []
    if (!amountPattern(selectedAccount.currency).test(reviseAmount.trim())) {
      problems.push(`金额需为正数且符合 ${selectedAccount.currency} 记账精度`)
    }
    if (!UUID_PATTERN.test(reviseCategoryId.trim())) problems.push('请填写有效的分类 ID')
    if (reviseReason.trim() === '') problems.push('修改原因不能为空')
    if (problems.length > 0) {
      setError(problems[0])
      return
    }
    setError(null)
    reviseMutation.mutate()
  }

  return (
    <main id="main-content" className="mx-auto flex w-full max-w-2xl flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-center gap-3">
        <h1 className="font-heading text-2xl font-semibold tracking-tight">
          {TYPE_LABELS[transaction.type] ?? transaction.type}
        </h1>
        <Badge>{STATUS_LABELS[transaction.status]}</Badge>
        <span className="text-sm text-muted-foreground">
          {transaction.businessDate} · {transaction.timezone}
        </span>
      </section>

      {error ? <p className="text-sm text-destructive" role="alert">{error}</p> : null}

      <Card>
        <CardHeader>
          <CardTitle>交易详情</CardTitle>
          <CardDescription>
            来源 {transaction.source} · 版本 {transaction.versionNo}（ETag {transaction.version}）
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <EntryList transaction={transaction} />
          <div className="grid gap-3 border-t pt-3 md:grid-cols-2">
            <VersionLink label="根交易 ID" value={transaction.rootTransactionId} />
            <VersionLink label="上一版本 ID" value={transaction.previousVersionId ?? null} />
            <VersionLink label="冲正对象 ID" value={transaction.reversalOfId ?? null} />
            <VersionLink label="交易 ID" value={transaction.id} />
          </div>
        </CardContent>
      </Card>

      {revisable ? (
        <Card>
          <CardHeader>
            <CardTitle>修改交易</CardTitle>
            <CardDescription>
              修订会冲正旧版本并创建新版本；需完整填写收入/支出替换命令，business_date 保持不变。
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {revising ? (
              <form className="flex flex-col gap-3" onSubmit={submitRevise} noValidate>
                <div className="flex flex-col gap-2">
                  <label htmlFor="revise-account">账户</label>
                  <select
                    id="revise-account"
                    value={reviseAccountId}
                    onChange={(event) => {
                      setReviseAccountId(event.target.value)
                      setError(null)
                    }}
                    className="h-9 rounded-md border bg-transparent px-3 text-sm"
                  >
                    <option value="">请选择账户</option>
                    {accounts.map((account) => (
                      <option key={account.id} value={account.id}>{account.name} · {account.currency}</option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <label htmlFor="revise-amount">金额（{reviseCurrency}）</label>
                  <Input
                    id="revise-amount"
                    inputMode="decimal"
                    value={reviseAmount}
                    onChange={(event) => {
                      setReviseAmount(event.target.value)
                      setError(null)
                    }}
                  />
                </div>
                <div className="flex flex-col gap-2">
                  <label htmlFor="revise-category">分类 ID</label>
                  <Input
                    id="revise-category"
                    value={reviseCategoryId}
                    placeholder="分类管理 API 完成前可直填 UUID"
                    onChange={(event) => {
                      setReviseCategoryId(event.target.value)
                      setError(null)
                    }}
                  />
                </div>
                <div className="flex flex-col gap-2">
                  <label htmlFor="revise-reason">修改原因</label>
                  <Input
                    id="revise-reason"
                    value={reviseReason}
                    onChange={(event) => {
                      setReviseReason(event.target.value)
                      setError(null)
                    }}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="submit" disabled={reviseMutation.isPending}>
                    {reviseMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                    提交修改
                  </Button>
                  <Button type="button" variant="outline" onClick={() => setRevising(false)}>取消</Button>
                </div>
              </form>
            ) : (
              <Button type="button" variant="outline" onClick={() => setRevising(true)}>修改此交易</Button>
            )}
          </CardContent>
        </Card>
      ) : null}

      {transaction.status === 'POSTED' ? (
        <Card>
          <CardHeader>
            <CardTitle>作废交易</CardTitle>
            <CardDescription>作废会创建冲正交易并保留原交易事实，不会物理删除。</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {voiding ? (
              <form
                className="flex flex-col gap-3"
                onSubmit={(event) => {
                  event.preventDefault()
                  if (voidReason.trim() === '') {
                    setError('作废原因不能为空')
                    return
                  }
                  setError(null)
                  reverseMutation.mutate()
                }}
                noValidate
              >
                <div className="flex flex-col gap-2">
                  <label htmlFor="void-reason">作废原因</label>
                  <Input
                    id="void-reason"
                    value={voidReason}
                    onChange={(event) => {
                      setVoidReason(event.target.value)
                      setError(null)
                    }}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="submit" variant="destructive" disabled={reverseMutation.isPending || voidReason.trim() === ''}>
                    {reverseMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                    确认作废
                  </Button>
                  <Button type="button" variant="outline" onClick={() => setVoiding(false)}>取消</Button>
                </div>
              </form>
            ) : (
              <Button type="button" variant="outline" onClick={() => setVoiding(true)}>作废此交易</Button>
            )}
          </CardContent>
        </Card>
      ) : null}
    </main>
  )
}
