import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  categoryEtag,
  createCategory,
  createTag,
  listCategories,
  listTags,
  mergeCategory,
  patchCategory,
  patchTag,
  type Category,
  type CategoryScope,
  type CategoryType,
  type Tag,
} from '@/categories/categories-api'

function StatusBadge({ status }: { status: string }) {
  if (status === 'ACTIVE') return <Badge variant="outline">启用</Badge>
  if (status === 'INACTIVE') return <Badge variant="secondary">停用</Badge>
  return <Badge variant="destructive">已合并</Badge>
}

/** 分类与标签管理：两级分类、停用/合并与标签维护；交易选择器复用同一查询缓存。 */
export function CategoriesPage() {
  const [tab, setTab] = useState<'categories' | 'tags'>('categories')
  const [message, setMessage] = useState<string | null>(null)

  const categoriesQuery = useQuery({ queryKey: ['categories', 'PERSONAL'], queryFn: () => listCategories('PERSONAL') })
  const tagsQuery = useQuery({ queryKey: ['tags'], queryFn: listTags })

  return (
    <main id="main-content" className="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-col gap-1">
        <Badge variant="outline">分类与标签</Badge>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">分类、标签与记账选择器</h1>
        <p className="text-sm text-muted-foreground">两级分类与账户/个人作用域；合并保留历史映射，已合并分类不能用于新交易。</p>
      </section>

      <div className="flex gap-2">
        {(['categories', 'tags'] as const).map((option) => (
          <Button key={option} variant={tab === option ? 'default' : 'outline'} onClick={() => setTab(option)}>
            {option === 'categories' ? '分类' : '标签'}
          </Button>
        ))}
      </div>

      {tab === 'categories' ? (
        <CategoryPanel
          categories={categoriesQuery.data ?? []}
          loading={categoriesQuery.isLoading}
          onError={setMessage}
        />
      ) : (
        <TagPanel tags={tagsQuery.data ?? []} loading={tagsQuery.isLoading} onError={setMessage} />
      )}

      {message ? <p role="alert" className="text-sm text-destructive">{message}</p> : null}
    </main>
  )
}

function CategoryPanel({ categories, loading, onError }: {
  categories: Category[]
  loading: boolean
  onError: (message: string) => void
}) {
  const queryClient = useQueryClient()
  const [scope, setScope] = useState<CategoryScope>('PERSONAL')
  const [categoryType, setCategoryType] = useState<CategoryType>('EXPENSE')
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState('')
  const [accountId, setAccountId] = useState('')
  const [mergeTarget, setMergeTarget] = useState<Record<string, string>>({})

  const createMutation = useMutation({
    mutationFn: () => createCategory({
      accountId: scope === 'ACCOUNT' ? accountId : null,
      categoryType,
      name: name.trim(),
      parentId: parentId === '' ? null : parentId,
    }),
    onSuccess: () => {
      setName('')
      setParentId('')
      setAccountId('')
      void queryClient.invalidateQueries({ queryKey: ['categories'] })
    },
    onError: (error) => onError((error as { message?: string }).message ?? '创建失败'),
  })

  const toggleMutation = useMutation({
    mutationFn: ({ category, target }: { category: Category; target: 'ACTIVE' | 'INACTIVE' }) =>
      patchCategory(category.id, categoryEtag(category.version), { status: target }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['categories'] }),
    onError: (error) => onError((error as { message?: string }).message ?? '状态更新失败'),
  })

  const mergeMutation = useMutation({
    mutationFn: ({ category }: { category: Category }) => {
      const target = mergeTarget[category.id]
      if (!target) throw new Error('请选择合并目标分类')
      return mergeCategory(category.id, categoryEtag(category.version), globalThis.crypto.randomUUID(), target)
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['categories'] }),
    onError: (error) => onError((error as { message?: string }).message ?? '合并失败'),
  })

  const activeCategories = categories.filter((category) => category.status === 'ACTIVE' && category.parentId === null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>新建分类</CardTitle>
        <CardDescription>自定义分类最多两级；同一作用域内同树分类名不能重复。</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-2">
          <label htmlFor="category-scope">作用域</label>
          <select id="category-scope" value={scope} onChange={(event) => setScope(event.target.value as CategoryScope)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
            <option value="PERSONAL">个人</option>
            <option value="ACCOUNT">账户（需账户 ID）</option>
          </select>
        </div>
        <div className="flex flex-col gap-2">
          <label htmlFor="category-type">类型</label>
          <select id="category-type" value={categoryType} onChange={(event) => setCategoryType(event.target.value as CategoryType)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
            <option value="EXPENSE">支出</option>
            <option value="INCOME">收入</option>
          </select>
        </div>
        <div className="flex flex-col gap-2">
          <label htmlFor="category-name">名称</label>
          <Input id="category-name" value={name} onChange={(event) => setName(event.target.value)} placeholder="如：餐饮" />
        </div>
        <div className="flex flex-col gap-2">
          <label htmlFor="category-parent">父分类（可选）</label>
          <select id="category-parent" value={parentId} onChange={(event) => setParentId(event.target.value)} className="h-9 rounded-md border bg-transparent px-3 text-sm">
            <option value="">无（一级）</option>
            {activeCategories.filter((category) => category.categoryType === categoryType).map((category) => (
              <option key={category.id} value={category.id}>{category.name}</option>
            ))}
          </select>
        </div>
        {scope === 'ACCOUNT' ? (
          <div className="flex flex-col gap-2 md:col-span-2">
            <label htmlFor="category-account">账户 ID</label>
            <Input id="category-account" value={accountId} onChange={(event) => setAccountId(event.target.value)} placeholder="账户 UUID" />
          </div>
        ) : null}
        <Button className="md:col-span-2" disabled={name.trim() === '' || createMutation.isPending}
          onClick={() => createMutation.mutate()}>
          创建分类
        </Button>
      </CardContent>

      <Card>
        <CardHeader>
          <CardTitle>分类列表</CardTitle>
          <CardDescription>停用仅影响新交易选择，历史交易映射保留；合并后源分类不可再用于新交易。</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {loading ? <p className="text-sm text-muted-foreground">正在加载…</p> : categories.length === 0 ? (
            <p className="text-sm text-muted-foreground">暂无分类，先在上方创建。</p>
          ) : (
            <ul className="grid gap-2">
              {categories.map((category) => {
                const parent = categories.find((candidate) => candidate.id === category.parentId)
                const mergeCandidates = categories.filter((candidate) =>
                  candidate.id !== category.id && candidate.status === 'ACTIVE'
                    && candidate.categoryType === category.categoryType && candidate.parentId === null)
                return (
                  <li key={category.id} className="grid gap-2 rounded-md border p-3 md:grid-cols-[1fr_auto_auto]">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium">{parent ? `${parent.name} / ` : ''}{category.name}</span>
                      <Badge variant="outline">{category.categoryType === 'EXPENSE' ? '支出' : '收入'}</Badge>
                      <StatusBadge status={category.status} />
                    </div>
                    <div className="flex items-center gap-2">
                      {category.status === 'ACTIVE' ? (
                        <Button variant="outline" size="sm" disabled={toggleMutation.isPending}
                          onClick={() => toggleMutation.mutate({ category, target: 'INACTIVE' })}>停用</Button>
                      ) : category.status === 'INACTIVE' ? (
                        <Button variant="outline" size="sm" disabled={toggleMutation.isPending}
                          onClick={() => toggleMutation.mutate({ category, target: 'ACTIVE' })}>启用</Button>
                      ) : null}
                    </div>
                    {category.status !== 'MERGED' ? (
                      <div className="flex items-center gap-2">
                        <select
                          aria-label={`合并目标-${category.name}`}
                          value={mergeTarget[category.id] ?? ''}
                          onChange={(event) => setMergeTarget((previous) => ({ ...previous, [category.id]: event.target.value }))}
                          className="h-9 rounded-md border bg-transparent px-3 text-sm"
                        >
                          <option value="">合并到…</option>
                          {mergeCandidates.map((candidate) => (
                            <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
                          ))}
                        </select>
                        <Button variant="outline" size="sm" disabled={mergeMutation.isPending}
                          onClick={() => mergeMutation.mutate({ category })}>合并</Button>
                      </div>
                    ) : (
                      <span className="text-sm text-muted-foreground">
                        {category.mergedIntoId ? '已合并，历史映射保留' : '已合并'}
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </CardContent>
      </Card>
    </Card>
  )
}

function TagPanel({ tags, loading, onError }: {
  tags: Tag[]
  loading: boolean
  onError: (message: string) => void
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')

  const createMutation = useMutation({
    mutationFn: () => createTag(name.trim()),
    onSuccess: () => {
      setName('')
      void queryClient.invalidateQueries({ queryKey: ['tags'] })
    },
    onError: (error) => onError((error as { message?: string }).message ?? '创建失败'),
  })

  const toggleMutation = useMutation({
    mutationFn: ({ tag, target }: { tag: Tag; target: 'ACTIVE' | 'INACTIVE' }) =>
      patchTag(tag.id, categoryEtag(tag.version), { status: target }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['tags'] }),
    onError: (error) => onError((error as { message?: string }).message ?? '状态更新失败'),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>标签管理</CardTitle>
        <CardDescription>标签用于跨分类标注交易；停用后不再出现在新交易选择器中。</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex gap-2">
          <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="标签名称" aria-label="标签名称" />
          <Button disabled={name.trim() === '' || createMutation.isPending} onClick={() => createMutation.mutate()}>创建标签</Button>
        </div>
        {loading ? <p className="text-sm text-muted-foreground">正在加载…</p> : tags.length === 0 ? (
          <p className="text-sm text-muted-foreground">暂无标签。</p>
        ) : (
          <ul className="grid gap-2">
            {tags.map((tag) => (
              <li key={tag.id} className="flex items-center justify-between gap-3 rounded-md border p-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{tag.name}</span>
                  <StatusBadge status={tag.status} />
                </div>
                {tag.status === 'ACTIVE' ? (
                  <Button variant="outline" size="sm" disabled={toggleMutation.isPending}
                    onClick={() => toggleMutation.mutate({ tag, target: 'INACTIVE' })}>停用</Button>
                ) : (
                  <Button variant="outline" size="sm" disabled={toggleMutation.isPending}
                    onClick={() => toggleMutation.mutate({ tag, target: 'ACTIVE' })}>启用</Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
