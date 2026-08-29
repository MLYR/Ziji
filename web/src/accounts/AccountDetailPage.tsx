import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircleIcon } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { describeProblem } from '@/lib/problem-messages'
import { LiabilityDetailsCard } from '@/accounts/LiabilityDetailsCard'
import {
  accountEtag,
  archiveAccount,
  fetchAccount,
  fetchAccountBalance,
  updateAccount,
} from '@/accounts/accounts-api'

const TYPE_LABELS: Record<string, string> = {
  BANK: '银行', WECHAT: '微信', ALIPAY: '支付宝', CASH: '现金', BROKERAGE: '券商', FUND: '基金',
  CREDIT_CARD: '信用卡', LOAN: '贷款', CONSUMER_LOAN: '消费贷款', OTHER: '其他',
}

export function AccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const accountQuery = useQuery({
    queryKey: ['account', accountId],
    queryFn: () => fetchAccount(accountId!),
    enabled: Boolean(accountId),
  })
  const balanceQuery = useQuery({
    queryKey: ['account-balance', accountId],
    queryFn: () => fetchAccountBalance(accountId!),
    enabled: Boolean(accountId),
    staleTime: 15_000,
  })

  const [editing, setEditing] = useState(false)
  const [name, setName] = useState('')
  const [institution, setInstitution] = useState('')
  const [archiving, setArchiving] = useState(false)
  const [reason, setReason] = useState('')
  const [confirmNonZero, setConfirmNonZero] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const account = accountQuery.data

  const updateMutation = useMutation({
    mutationFn: () => updateAccount(accountId!, accountEtag(account!.version), { name: name.trim(), institution: institution.trim() === '' ? null : institution.trim() }),
    onSuccess: () => {
      setEditing(false)
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['account', accountId] })
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
    onError: (mutationError) => setError(describeProblem(mutationError)),
  })

  const archiveMutation = useMutation({
    mutationFn: () => archiveAccount(
      accountId!,
      accountEtag(account!.version),
      crypto.randomUUID(),
      { reason: reason.trim(), confirmNonZeroBalance: confirmNonZero },
    ),
    onSuccess: () => {
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
      void navigate('/accounts')
    },
    onError: (mutationError) => setError(describeProblem(mutationError)),
  })

  if (accountQuery.isPending) {
    return <main id="main-content" className="p-6 lg:p-8" aria-busy="true"><p className="text-sm text-muted-foreground">正在加载账户…</p></main>
  }
  if (accountQuery.isError || !account) {
    return (
      <main id="main-content" className="p-6 lg:p-8">
        <Card className="mx-auto max-w-md">
          <CardHeader><CardTitle>无法加载账户</CardTitle><CardDescription>账户可能不存在或不可见。</CardDescription></CardHeader>
          <CardContent><Button variant="outline" asChild><Link to="/accounts">返回账户列表</Link></Button></CardContent>
        </Card>
      </main>
    )
  }

  const balance = balanceQuery.data
  const hasNonZeroBalance = balance ? Number(balance.ledgerBalance) !== 0 : false

  return (
    <main id="main-content" className="mx-auto flex w-full max-w-2xl flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-center gap-3">
        <h1 className="font-heading text-2xl font-semibold tracking-tight">{account.name}</h1>
        <Badge variant="outline">{TYPE_LABELS[account.accountType] ?? account.accountType}</Badge>
        <Badge variant="secondary">{account.currency}</Badge>
        {account.status === 'ARCHIVED' ? <Badge variant="outline">已归档</Badge> : null}
      </section>

      {error ? <p className="text-sm text-destructive" role="alert">{error}</p> : null}

      <Card>
        <CardHeader>
          <CardTitle>余额</CardTitle>
          <CardDescription>来自服务端余额投影</CardDescription>
        </CardHeader>
        <CardContent>
          {balance ? (
            <div className="flex flex-col gap-1">
              <p className="font-heading text-2xl font-semibold">{balance.ledgerBalance} {balance.currency}</p>
              <p className="text-sm text-muted-foreground">
                可用 {balance.availableBalance} · 不可用 {balance.unavailableAmount}
                （冻结 {balance.unavailableBreakdown.frozen} / 在途 {balance.unavailableBreakdown.inTransit} / 保留 {balance.unavailableBreakdown.reserved}）
              </p>
              {balance.liquidityStatus === 'NEGATIVE_AVAILABLE' ? (
                <p className="text-sm font-semibold text-amber-600">可用余额为负：透支或冻结金额超过账面余额。</p>
              ) : null}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">余额加载中…</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>账户信息</CardTitle>
          <CardDescription>机构与名称可编辑；修改需要 If-Match 乐观并发。</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {editing ? (
            <form className="flex flex-col gap-3" onSubmit={(event) => { event.preventDefault(); updateMutation.mutate() }} noValidate>
              <div className="flex flex-col gap-2">
                <label htmlFor="edit-name">名称</label>
                <Input id="edit-name" value={name} onChange={(event) => setName(event.target.value)} />
              </div>
              <div className="flex flex-col gap-2">
                <label htmlFor="edit-institution">机构（可选）</label>
                <Input id="edit-institution" value={institution} onChange={(event) => setInstitution(event.target.value)} />
              </div>
              <div className="flex gap-2">
                <Button type="submit" disabled={updateMutation.isPending}>
                  {updateMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                  保存
                </Button>
                <Button type="button" variant="outline" onClick={() => setEditing(false)}>取消</Button>
              </div>
            </form>
          ) : (
            <div className="flex flex-col gap-2">
              <p className="text-sm"><span className="text-muted-foreground">机构：</span>{account.institution ?? '—'}</p>
              {account.status === 'ACTIVE' ? (
                <Button variant="outline" onClick={() => { setName(account.name); setInstitution(account.institution ?? ''); setEditing(true) }}>
                  编辑账户
                </Button>
              ) : null}
            </div>
          )}
        </CardContent>
      </Card>

      {account.accountClass === 'LIABILITY' ? <LiabilityDetailsCard account={account} /> : null}

      {account.status === 'ACTIVE' ? (
        <Card>
          <CardHeader>
            <CardTitle>归档</CardTitle>
            <CardDescription>归档后账户退出日常列表与统计，历史保留。</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {archiving ? (
              <form className="flex flex-col gap-3" onSubmit={(event) => { event.preventDefault(); archiveMutation.mutate() }} noValidate>
                <div className="flex flex-col gap-2">
                  <label htmlFor="archive-reason">归档原因</label>
                  <Input id="archive-reason" value={reason} onChange={(event) => setReason(event.target.value)} placeholder="例如：不再使用" />
                </div>
                {hasNonZeroBalance ? (
                  <div className="flex items-center gap-2 text-sm">
                    <input id="archive-confirm" type="checkbox" checked={confirmNonZero}
                      onChange={(event) => setConfirmNonZero(event.target.checked)} className="size-4" />
                    <label htmlFor="archive-confirm">账户余额非零，我确认仍要归档</label>
                  </div>
                ) : null}
                <div className="flex gap-2">
                  <Button type="submit" variant="destructive" disabled={archiveMutation.isPending || (hasNonZeroBalance && !confirmNonZero)}>
                    {archiveMutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                    确认归档
                  </Button>
                  <Button type="button" variant="outline" onClick={() => setArchiving(false)}>取消</Button>
                </div>
              </form>
            ) : (
              <Button variant="outline" onClick={() => setArchiving(true)}>归档账户</Button>
            )}
          </CardContent>
        </Card>
      ) : null}
    </main>
  )
}
