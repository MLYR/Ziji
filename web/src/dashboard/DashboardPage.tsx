import { useQuery } from '@tanstack/react-query'
import { AlertCircleIcon, LoaderCircleIcon } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { fetchAccountStatistics, fetchAssetStatistics, fetchDashboard, fetchRecentTransactions } from '@/dashboard/dashboard-api'
import { TrendChart } from '@/dashboard/TrendChart'

const WARNING_LABELS: Record<string, string> = {
  MISSING_EXCHANGE_RATES: '部分账户缺少汇率，未计入折算总额',
  UNPRICED_INSTRUMENTS: '部分持仓缺少价格，未计入估值',
  STALE_MARKET_DATA: '行情数据过期',
}

const TYPE_LABELS: Record<string, string> = {
  INCOME: '收入',
  EXPENSE: '支出',
  REFUND: '退款',
  TRANSFER: '转账',
  ADJUSTMENT: '余额调整',
  OPENING: '期初',
  REVERSAL: '冲正',
  REPAYMENT: '负债还款',
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function daysAgoIso(days: number): string {
  return new Date(Date.now() - days * 86400000).toISOString().slice(0, 10)
}

function useDashboard() {
  return useQuery({ queryKey: ['dashboard'], queryFn: fetchDashboard, staleTime: 30_000 })
}

function useAssetTrend(enabled: boolean) {
  return useQuery({
    queryKey: ['statistics', 'assets', daysAgoIso(29), todayIso(), 'DAY'],
    queryFn: () => fetchAssetStatistics(daysAgoIso(29), todayIso(), 'DAY'),
    enabled,
    staleTime: 60_000,
  })
}

function useAccountStructure(enabled: boolean) {
  return useQuery({
    queryKey: ['statistics', 'accounts', daysAgoIso(29), todayIso(), 'DAY'],
    queryFn: () => fetchAccountStatistics(daysAgoIso(29), todayIso(), 'DAY'),
    enabled,
    staleTime: 60_000,
  })
}

function useRecentTransactions() {
  return useQuery({ queryKey: ['transactions', 'recent'], queryFn: () => fetchRecentTransactions(5), staleTime: 30_000 })
}

export function DashboardPage() {
  const dashboard = useDashboard()
  const data = dashboard.data
  const assetTrend = useAssetTrend(Boolean(data))
  const accountStructure = useAccountStructure(Boolean(data))
  const recent = useRecentTransactions()

  if (dashboard.isPending) {
    return (
      <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8" aria-busy="true">
        <Skeleton className="h-9 w-56" />
        <div className="grid gap-4 md:grid-cols-3 xl:grid-cols-5">
          {['总资产', '可用资金', '投资资产', '总负债', '净资产'].map((label) => (
            <Card key={label}>
              <CardHeader><CardTitle>{label}</CardTitle></CardHeader>
              <CardContent><Skeleton className="h-8 w-2/3" /></CardContent>
            </Card>
          ))}
        </div>
      </main>
    )
  }

  if (dashboard.isError || !data || !data.summary) {
    return (
      <main id="main-content" className="p-6 lg:p-8">
        <Card className="mx-auto max-w-md">
          <CardHeader>
            <CardTitle>无法加载总览</CardTitle>
            <CardDescription>网络或服务暂时不可用。</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => void dashboard.refetch()}>
              {dashboard.isFetching ? <LoaderCircleIcon className="animate-spin" /> : null}
              重试
            </Button>
          </CardContent>
        </Card>
      </main>
    )
  }

  const summary = data.summary
  const metrics: { label: string; value: string }[] = [
    { label: '总资产', value: summary.totalAssets },
    { label: '可用资金', value: summary.availableFunds },
    { label: '投资资产', value: summary.investmentAssets },
    { label: '总负债', value: summary.totalLiabilities },
    { label: '净资产', value: summary.netAssets },
  ]
  const trend = assetTrend.data
  const structure = accountStructure.data
  const latestStructure = structure?.points.at(-1)?.values ?? {}

  return (
    <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Badge variant="outline">{data.baseCurrency}</Badge>
            <Badge variant={data.projectionStatus === 'CURRENT' ? 'secondary' : 'destructive'}>
              {data.projectionStatus}
            </Badge>
            <span className="text-xs text-muted-foreground">估值修订 v{data.valuationRevision}</span>
          </div>
          <h1 className="font-heading text-2xl font-semibold tracking-tight">总览</h1>
          <p className="text-sm text-muted-foreground">数据截至 {data.asOf} · 变更序列 {data.asOfSequence}</p>
        </div>
        <Button asChild><Link to="/transactions/new">记一笔</Link></Button>
      </section>

      {data.dataQualityWarnings.length > 0 ? (
        <div className="flex flex-col gap-2 rounded-md border border-amber-600/40 bg-amber-500/10 p-4" data-testid="quality-warnings">
          {data.dataQualityWarnings.map((warning) => (
            <div key={warning.code} className="flex items-center gap-2 text-sm">
              <AlertCircleIcon className="size-4 text-amber-600" />
              <span>{WARNING_LABELS[warning.code] ?? warning.code}（{warning.affectedCount} 项未计入）</span>
            </div>
          ))}
        </div>
      ) : null}

      <section className="grid gap-4 md:grid-cols-3 xl:grid-cols-5" aria-label="核心指标">
        {metrics.map(({ label, value }) => (
          <Card key={label}>
            <CardHeader>
              <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="font-heading text-2xl font-semibold tracking-tight" data-testid={`metric-${label}`}>
                {value}
                <span className="ml-1 text-xs text-muted-foreground">{data.baseCurrency}</span>
              </p>
            </CardContent>
          </Card>
        ))}
      </section>

      <section className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>净资产趋势（近 30 天）</CardTitle>
            <CardDescription>统计 · 服务端序列</CardDescription>
          </CardHeader>
          <CardContent>
            {assetTrend.isPending ? (
              <Skeleton className="h-56 w-full" />
            ) : trend ? (
              <TrendChart
                title="净资产"
                labels={trend.points.map((point) => point.businessDate)}
                values={trend.points.map((point) => point.values.netAssets ?? '0')}
              />
            ) : (
              <p className="text-sm text-muted-foreground">趋势暂不可用。</p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>账户结构</CardTitle>
            <CardDescription>最近统计日的账户余额占比</CardDescription>
          </CardHeader>
          <CardContent>
            {accountStructure.isPending ? (
              <Skeleton className="h-40 w-full" />
            ) : structure && Object.keys(latestStructure).length > 0 ? (
              <ul className="flex flex-col gap-2" data-testid="account-structure">
                {Object.entries(latestStructure).map(([accountId, balance]) => (
                  <li key={accountId} className="flex items-center justify-between gap-2 text-sm">
                    <span className="truncate font-mono text-xs text-muted-foreground">{accountId}</span>
                    <span className="font-medium">{balance} {structure.baseCurrency}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">尚无账户结构数据。</p>
            )}
          </CardContent>
        </Card>
      </section>

      <Card>
        <CardHeader>
          <CardTitle>近期流水</CardTitle>
          <CardDescription>最近 5 笔交易</CardDescription>
          <CardAction><Button variant="ghost" asChild><Link to="/transactions">查看全部</Link></Button></CardAction>
        </CardHeader>
        <CardContent>
          {recent.isPending ? (
            <Skeleton className="h-24 w-full" />
          ) : recent.data && recent.data.transactions.length > 0 ? (
            <ul className="flex flex-col gap-2" data-testid="recent-transactions">
              {recent.data.transactions.map((transaction) => (
                <li key={transaction.id} className="flex items-center justify-between gap-2 border-b pb-2 text-sm last:border-b-0">
                  <span>{transaction.businessDate}</span>
                  <span>{TYPE_LABELS[transaction.type] ?? transaction.type}</span>
                  <span className="text-muted-foreground">{transaction.status}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">还没有交易记录。</p>
          )}
        </CardContent>
      </Card>
    </main>
  )
}
