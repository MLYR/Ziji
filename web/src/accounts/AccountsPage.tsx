import { useQuery } from '@tanstack/react-query'
import { AlertCircleIcon, LoaderCircleIcon, PlusIcon } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { fetchAccountBalance, listAccounts, type Account } from '@/accounts/accounts-api'

const GROUPS: { key: Account['accountClass']; label: string; description: string }[] = [
  { key: 'ASSET', label: '资产账户', description: '现金、银行与微信/支付宝等资金账户' },
  { key: 'INVESTMENT', label: '投资账户', description: '券商现金与投资持仓' },
  { key: 'LIABILITY', label: '负债账户', description: '信用卡、贷款等债务账户' },
]

const NEGATIVE_HINT = '可用余额为负：存在透支或冻结金额大于账面余额。'

function BalanceRow({ accountId }: { accountId: string }) {
  const balance = useQuery({
    queryKey: ['account-balance', accountId],
    queryFn: () => fetchAccountBalance(accountId),
    staleTime: 15_000,
  })

  if (balance.isPending) return <Skeleton className="h-6 w-24" />
  if (balance.isError) return <span className="text-xs text-muted-foreground">余额不可用</span>

  const data = balance.data
  return (
    <div className="text-right">
      <p className="font-heading text-lg font-semibold tracking-tight" data-testid={`balance-${accountId}`}>
        {data.ledgerBalance} <span className="text-xs text-muted-foreground">{data.currency}</span>
      </p>
      <p className="text-xs text-muted-foreground">
        可用 {data.availableBalance}
        {data.liquidityStatus === 'NEGATIVE_AVAILABLE' ? (
          <span className="ml-1 font-semibold text-amber-600" title={NEGATIVE_HINT}>⚠</span>
        ) : null}
      </p>
    </div>
  )
}

function AccountGroup({
  group,
  accounts,
}: {
  group: (typeof GROUPS)[number]
  accounts: Account[]
}) {
  const groupAccounts = accounts.filter((account) => account.accountClass === group.key)
  if (groupAccounts.length === 0) return null

  return (
    <section className="flex flex-col gap-3" aria-label={group.label}>
      <div>
        <h2 className="font-heading text-lg font-semibold tracking-tight">{group.label}</h2>
        <p className="text-xs text-muted-foreground">{group.description}</p>
      </div>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {groupAccounts.map((account) => (
          <Card key={account.id}>
            <CardHeader>
              <CardTitle className="text-base">{account.name}</CardTitle>
              <CardDescription>
                {account.accountType} · {account.currency}
                {account.status === 'ARCHIVED' ? <Badge variant="outline" className="ml-2">已归档</Badge> : null}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {account.status === 'ACTIVE' ? (
                <BalanceRow accountId={account.id} />
              ) : (
                <p className="text-xs text-muted-foreground">归档账户不参与日常统计。</p>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  )
}

export function AccountsPage() {
  const accountsQuery = useQuery({ queryKey: ['accounts', 'list'], queryFn: listAccounts, staleTime: 30_000 })
  const accounts = accountsQuery.data?.accounts ?? []

  if (accountsQuery.isPending) {
    return (
      <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8" aria-busy="true">
        <Skeleton className="h-9 w-40" />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {[1, 2, 3].map((key) => <Skeleton key={key} className="h-28 w-full" />)}
        </div>
      </main>
    )
  }

  if (accountsQuery.isError) {
    return (
      <main id="main-content" className="p-6 lg:p-8">
        <Card className="mx-auto max-w-md">
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><AlertCircleIcon className="size-4" />无法加载账户</CardTitle>
            <CardDescription>网络或服务暂时不可用。</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => void accountsQuery.refetch()}>
              {accountsQuery.isFetching ? <LoaderCircleIcon className="animate-spin" /> : null}
              重试
            </Button>
          </CardContent>
        </Card>
      </main>
    )
  }

  if (accounts.length === 0) {
    return (
      <main id="main-content" className="p-6 lg:p-8">
        <Card className="mx-auto max-w-md">
          <CardHeader>
            <CardTitle>还没有账户</CardTitle>
            <CardDescription>创建第一个账户后即可开始记账。</CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild><Link to="/accounts/new"><PlusIcon data-icon="inline-start" />创建账户</Link></Button>
          </CardContent>
        </Card>
      </main>
    )
  }

  return (
    <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="font-heading text-2xl font-semibold tracking-tight">账户</h1>
          <p className="text-sm text-muted-foreground">按大类分组，余额来自服务端余额投影。</p>
        </div>
        <Button asChild><Link to="/accounts/new"><PlusIcon data-icon="inline-start" />创建账户</Link></Button>
      </section>

      {GROUPS.map((group) => (
        <AccountGroup key={group.key} group={group} accounts={accounts} />
      ))}
    </main>
  )
}
