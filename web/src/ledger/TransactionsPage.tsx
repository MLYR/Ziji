import { useQuery } from '@tanstack/react-query'
import { LoaderCircleIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { listTransactions, type TransactionType } from '@/ledger/transaction-api'

const TYPE_OPTIONS: { value: TransactionType; label: string }[] = [
  { value: 'OPENING', label: '期初' },
  { value: 'INCOME', label: '收入' },
  { value: 'EXPENSE', label: '支出' },
  { value: 'REFUND', label: '退款' },
  { value: 'TRANSFER', label: '转账' },
  { value: 'FX_TRANSFER', label: '跨币种转账' },
  { value: 'ADJUSTMENT', label: '余额调整' },
  { value: 'INVESTMENT', label: '投资' },
  { value: 'REPAYMENT', label: '负债还款' },
  { value: 'INTEREST', label: '利息' },
  { value: 'REVERSAL', label: '冲正' },
]

const TYPE_LABELS = new Map(TYPE_OPTIONS.map(({ value, label }) => [value, label]))
const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  POSTED: '已入账',
  REVERSED: '已作废',
  SUPERSEDED: '已替代',
  DISCARDED: '已丢弃',
}
const STATUS_VARIANTS: Record<string, 'default' | 'outline' | 'secondary'> = {
  POSTED: 'default',
  REVERSED: 'outline',
  SUPERSEDED: 'outline',
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function TransactionsPage() {
  const [type, setType] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [cursor, setCursor] = useState<string | null>(null)

  const filters = {
    type: (TYPE_LABELS.has(type as TransactionType) ? type : undefined) as TransactionType | undefined,
    categoryId: UUID_PATTERN.test(categoryId) ? categoryId : undefined,
    dateFrom: dateFrom || undefined,
    dateTo: dateTo || undefined,
    limit: 50,
    cursor: cursor ?? undefined,
  }

  const listQuery = useQuery({
    queryKey: ['transactions', 'list', filters],
    queryFn: () => listTransactions(filters),
    staleTime: 15_000,
    placeholderData: (previous) => previous,
  })

  const transactions = listQuery.data?.transactions ?? []
  const hasFilter = type !== '' || categoryId !== '' || dateFrom !== '' || dateTo !== ''

  function clearFilters() {
    setType('')
    setCategoryId('')
    setDateFrom('')
    setDateTo('')
    setCursor(null)
  }

  return (
    <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="font-heading text-2xl font-semibold tracking-tight">流水</h1>
          <p className="text-sm text-muted-foreground">按业务日期倒序的交易记录。</p>
        </div>
        <Button asChild><Link to="/transactions/new">记一笔</Link></Button>
      </section>

      <Card>
        <CardHeader>
          <CardTitle>筛选</CardTitle>
          <CardDescription>类型、分类 ID 和业务日期范围；修改筛选后从头分页。</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-type" className="text-xs text-muted-foreground">类型</label>
            <select
              id="filter-type"
              value={type}
              onChange={(event) => {
                setType(event.target.value)
                setCursor(null)
              }}
              className="h-9 rounded-md border bg-transparent px-3 text-sm"
            >
              <option value="">全部</option>
              {TYPE_OPTIONS.map(({ value, label }) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-category" className="text-xs text-muted-foreground">分类 ID</label>
            <Input
              id="filter-category"
              value={categoryId}
              placeholder="分类管理 API 完成前可直填 UUID"
              onChange={(event) => {
                setCategoryId(event.target.value)
                setCursor(null)
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-from" className="text-xs text-muted-foreground">开始日期</label>
            <Input
              id="filter-from"
              type="date"
              value={dateFrom}
              onChange={(event) => {
                setDateFrom(event.target.value)
                setCursor(null)
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-to" className="text-xs text-muted-foreground">结束日期</label>
            <Input
              id="filter-to"
              type="date"
              value={dateTo}
              onChange={(event) => {
                setDateTo(event.target.value)
                setCursor(null)
              }}
            />
          </div>
          {hasFilter ? (
            <Button type="button" variant="ghost" onClick={clearFilters}>清除筛选</Button>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>交易记录</CardTitle>
          <CardDescription>{listQuery.isFetching ? '加载中…' : `当前页 ${transactions.length} 条`}</CardDescription>
          <CardAction>
            {listQuery.isFetching ? <LoaderCircleIcon className="size-4 animate-spin text-muted-foreground" /> : null}
          </CardAction>
        </CardHeader>
        <CardContent>
          {listQuery.isPending ? (
            <p className="text-sm text-muted-foreground" aria-busy="true">正在加载流水…</p>
          ) : listQuery.isError ? (
            <div className="flex flex-col items-start gap-2">
              <p className="text-sm text-destructive" role="alert">无法加载流水，请稍后重试。</p>
              <Button type="button" variant="outline" onClick={() => void listQuery.refetch()}>重试</Button>
            </div>
          ) : transactions.length === 0 ? (
            <p className="text-sm text-muted-foreground">没有匹配的交易。</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {transactions.map((transaction) => (
                <li key={transaction.id} className="flex items-center justify-between gap-2 border-b pb-2 text-sm last:border-b-0">
                  <Link
                    to={`/transactions/${transaction.id}`}
                    className="min-w-0 flex-1 truncate hover:underline"
                  >
                    {transaction.businessDate} · {TYPE_LABELS.get(transaction.type) ?? transaction.type}
                  </Link>
                  <Badge variant={STATUS_VARIANTS[transaction.status] ?? 'secondary'}>
                    {STATUS_LABELS[transaction.status] ?? transaction.status}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
          {listQuery.data?.hasMore ? (
            <Button
              type="button"
              variant="outline"
              className="mt-4"
              disabled={listQuery.isFetching}
              onClick={() => setCursor(listQuery.data?.nextCursor ?? null)}
            >
              加载更多
            </Button>
          ) : null}
        </CardContent>
      </Card>
    </main>
  )
}
