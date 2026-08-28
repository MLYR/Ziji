import { useMutation } from '@tanstack/react-query'
import { LoaderCircleIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { describeProblem } from '@/lib/problem-messages'
import { createAccount, type Currency } from '@/accounts/accounts-api'

const TYPE_MATRIX: Record<'ASSET' | 'INVESTMENT' | 'LIABILITY', { value: string; label: string }[]> = {
  ASSET: [
    { value: 'BANK', label: '银行' },
    { value: 'WECHAT', label: '微信' },
    { value: 'ALIPAY', label: '支付宝' },
    { value: 'CASH', label: '现金' },
    { value: 'OTHER', label: '其他' },
  ],
  INVESTMENT: [
    { value: 'BROKERAGE', label: '券商' },
    { value: 'FUND', label: '基金' },
    { value: 'OTHER', label: '其他' },
  ],
  LIABILITY: [
    { value: 'CREDIT_CARD', label: '信用卡' },
    { value: 'LOAN', label: '贷款' },
    { value: 'CONSUMER_LOAN', label: '消费贷款' },
    { value: 'OTHER', label: '其他' },
  ],
}

const CURRENCIES: Currency[] = ['CNY', 'USD', 'HKD', 'JPY', 'EUR']

function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2
}

export function CreateAccountPage() {
  const navigate = useNavigate()
  const [accountClass, setAccountClass] = useState<'ASSET' | 'INVESTMENT' | 'LIABILITY'>('ASSET')
  const [accountType, setAccountType] = useState('BANK')
  const [name, setName] = useState('')
  const [currency, setCurrency] = useState<Currency>('CNY')
  const [institution, setInstitution] = useState('')
  const [note, setNote] = useState('')
  const [useOpening, setUseOpening] = useState(true)
  const [openingAmount, setOpeningAmount] = useState('')
  const [openingNote, setOpeningNote] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const idempotencyKey = useMemo(() => crypto.randomUUID(), [])
  const openingBusinessAt = useMemo(() => new Date().toISOString(), [])

  const mutation = useMutation({
    mutationFn: () =>
      createAccount(idempotencyKey, {
        accountClass,
        accountType,
        name: name.trim(),
        currency,
        institution: institution.trim() === '' ? null : institution.trim(),
        note: note.trim() === '' ? null : note.trim(),
        openingBalance: useOpening && openingAmount.trim() !== ''
          ? { amount: openingAmount.trim(), businessAt: openingBusinessAt, note: openingNote.trim() === '' ? null : openingNote.trim() }
          : null,
      }),
    onSuccess: () => {
      void navigate('/accounts')
    },
    onError: (error) => setFormError(
      error instanceof Error ? error.message : '创建失败，请稍后重试。',
    ),
  })

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const errors: Record<string, string> = {}
    if (name.trim() === '') errors.name = '账户名称不能为空'
    if (useOpening && openingAmount.trim() !== '') {
      const decimals = currencyMinorUnits(currency)
      const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`)
      if (!pattern.test(openingAmount.trim())) errors.openingAmount = `期初金额需符合 ${currency} 精度（最多 ${decimals} 位小数）`
    }
    setFieldErrors(errors)
    setFormError(Object.keys(errors).length > 0 ? '请先修正表单中的问题' : null)
    if (Object.keys(errors).length > 0) return
    mutation.mutate()
  }

  return (
    <main id="main-content" className="mx-auto flex w-full max-w-2xl flex-col gap-6 p-6 lg:p-8">
      <section className="flex flex-col gap-1">
        <Badge variant="outline">创建账户</Badge>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">新账户</h1>
        <p className="text-sm text-muted-foreground">大类决定可用子类型；期初余额在创建时原子入账。</p>
      </section>

      <Card>
        <CardHeader>
          <CardTitle>账户信息</CardTitle>
          <CardDescription>客户端只提交语义字段，分录由服务端生成。</CardDescription>
        </CardHeader>
        <CardContent>
          {formError ? <p className="mb-4 text-sm text-destructive" role="alert">{formError}</p> : null}
          {mutation.isError ? (
            <p className="mb-4 text-sm text-destructive" role="alert">{describeProblem(mutation.error)}</p>
          ) : null}
          <form className="grid gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-2">
              <label htmlFor="account-class">大类</label>
              <select id="account-class" value={accountClass}
                onChange={(event) => {
                  const next = event.target.value as 'ASSET' | 'INVESTMENT' | 'LIABILITY'
                  setAccountClass(next)
                  setAccountType(TYPE_MATRIX[next][0].value)
                }}
                className="h-9 rounded-md border bg-transparent px-3 text-sm">
                <option value="ASSET">资产</option>
                <option value="INVESTMENT">投资</option>
                <option value="LIABILITY">负债</option>
              </select>
            </div>

            <div className="flex flex-col gap-2">
              <label htmlFor="account-type">子类型</label>
              <select id="account-type" value={accountType} onChange={(event) => setAccountType(event.target.value)}
                className="h-9 rounded-md border bg-transparent px-3 text-sm">
                {TYPE_MATRIX[accountClass].map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </div>

            <div className="flex flex-col gap-2">
              <label htmlFor="account-name">账户名称</label>
              <Input id="account-name" value={name} onChange={(event) => setName(event.target.value)}
                aria-invalid={fieldErrors.name ? true : undefined} placeholder="例如：招商银行工资卡" />
              {fieldErrors.name ? <p className="text-sm text-destructive">{fieldErrors.name}</p> : null}
            </div>

            <div className="flex flex-col gap-2">
              <label htmlFor="account-currency">币种</label>
              <select id="account-currency" value={currency} onChange={(event) => setCurrency(event.target.value as Currency)}
                className="h-9 rounded-md border bg-transparent px-3 text-sm">
                {CURRENCIES.map((code) => <option key={code} value={code}>{code}</option>)}
              </select>
            </div>

            <div className="flex flex-col gap-2">
              <label htmlFor="account-institution">机构（可选）</label>
              <Input id="account-institution" value={institution} onChange={(event) => setInstitution(event.target.value)} />
            </div>

            <div className="flex flex-col gap-2">
              <label htmlFor="account-note">备注（可选）</label>
              <Input id="account-note" value={note} onChange={(event) => setNote(event.target.value)} />
            </div>

            <div className="flex items-center gap-2">
              <input id="account-use-opening" type="checkbox" checked={useOpening}
                onChange={(event) => setUseOpening(event.target.checked)} className="size-4" />
              <label htmlFor="account-use-opening">登记期初余额</label>
            </div>

            {useOpening ? (
              <>
                <div className="flex flex-col gap-2">
                  <label htmlFor="opening-amount">期初金额</label>
                  <Input id="opening-amount" inputMode="decimal" value={openingAmount}
                    onChange={(event) => setOpeningAmount(event.target.value)}
                    aria-invalid={fieldErrors.openingAmount ? true : undefined} placeholder="0.00" />
                  {fieldErrors.openingAmount ? <p className="text-sm text-destructive">{fieldErrors.openingAmount}</p> : null}
                </div>
                <div className="flex flex-col gap-2">
                  <label htmlFor="opening-note">期初备注（可选）</label>
                  <Input id="opening-note" value={openingNote} onChange={(event) => setOpeningNote(event.target.value)} />
                </div>
              </>
            ) : null}

            <div className="flex items-center gap-3">
              <Button type="submit" disabled={mutation.isPending}>
                {mutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                创建账户
              </Button>
              <p className="text-xs text-muted-foreground">创建请求携带幂等键，重复提交不会创建多个账户。</p>
            </div>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
