import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircleIcon } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import type { components } from '@ziji/api-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { describeProblem } from '@/lib/problem-messages'
import {
  fetchLiabilityDetails,
  putLiabilityDetails,
  type Account,
  type LiabilityDetail,
  type PutLiabilityDetailBody,
} from '@/accounts/accounts-api'

type NonNegativePostedMoney = components['schemas']['NonNegativePostedMoney']

interface LiabilityDetailsForm {
  interestRate: string
  loanDate: string
  dueDate: string
  billingDay: string
  repaymentDay: string
  currentAmountDue: string
}

interface FormErrors {
  fields: Record<string, string>
}

const EMPTY_FORM: LiabilityDetailsForm = {
  interestRate: '',
  loanDate: '',
  dueDate: '',
  billingDay: '',
  repaymentDay: '',
  currentAmountDue: '',
}

function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2
}

function amountPattern(currency: string): RegExp {
  const decimals = currencyMinorUnits(currency)
  return decimals === 0 ? /^[0-9]+$/ : new RegExp(`^[0-9]+(\\.[0-9]{1,${decimals}})?$`)
}

function formatAmount(value: string, currency: string): string {
  const [integer, fraction = ''] = value.trim().split('.')
  const decimals = currencyMinorUnits(currency)
  if (decimals === 0) return integer
  return `${integer}.${fraction.padEnd(decimals, '0')}`
}

function formFromDetail(detail: LiabilityDetail): LiabilityDetailsForm {
  return {
    interestRate: detail.interestRate ?? '',
    loanDate: detail.loanDate ?? '',
    dueDate: detail.dueDate ?? '',
    billingDay: detail.billingDay?.toString() ?? '',
    repaymentDay: detail.repaymentDay?.toString() ?? '',
    currentAmountDue: detail.currentAmountDue ?? '',
  }
}

function isApplicable(accountType: Account['accountType'], field: keyof LiabilityDetailsForm): boolean {
  if (accountType === 'CREDIT_CARD') return !['loanDate', 'dueDate'].includes(field)
  if (accountType === 'LOAN' || accountType === 'CONSUMER_LOAN') return field !== 'billingDay'
  return true
}

function fieldValue(form: LiabilityDetailsForm, field: keyof LiabilityDetailsForm, accountType: Account['accountType']): string | null {
  return isApplicable(accountType, field) && form[field].trim() !== '' ? form[field].trim() : null
}

/** 负债详情只保存提醒元数据；账面负债始终来自 LedgerEntry，不能由此表单覆盖。 */
export function LiabilityDetailsCard({ account }: { account: Account }) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState<LiabilityDetailsForm>(EMPTY_FORM)
  const [errors, setErrors] = useState<FormErrors>({ fields: {} })
  const [error, setError] = useState<string | null>(null)

  const detailsQuery = useQuery({
    queryKey: ['liability-details', account.id],
    queryFn: () => fetchLiabilityDetails(account.id),
    staleTime: 15_000,
  })
  const details = detailsQuery.data
  const canWrite = account.status === 'ACTIVE' && account.currentUserRole !== 'VIEWER'

  useEffect(() => {
    if (details !== undefined && !editing) setForm(formFromDetail(details))
  }, [details, editing])

  const payload = useMemo(() => JSON.stringify({ accountType: account.accountType, currency: account.currency, form }), [account.accountType, account.currency, form])
  // 幂等键与完整详情载荷绑定；提交失败后的相同表单会安全重试，编辑后则使用新键。
  // oxlint-disable-next-line react-hooks/exhaustive-deps -- randomUUID 是刻意的副作用键，只随载荷变化重建。
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [payload])

  const mutation = useMutation({
    mutationFn: () => putLiabilityDetails(account.id, details!.version, idempotencyKey, toRequestBody(form, account)),
    onSuccess: (updated) => {
      queryClient.setQueryData(['liability-details', account.id], updated)
      setEditing(false)
      setError(null)
      setErrors({ fields: {} })
    },
    onError: (mutationError) => setError(describeProblem(mutationError)),
  })

  function setValue(field: keyof LiabilityDetailsForm, value: string) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  function validate(): FormErrors {
    const fields: Record<string, string> = {}
    const interestRate = fieldValue(form, 'interestRate', account.accountType)
    const currentAmountDue = fieldValue(form, 'currentAmountDue', account.accountType)
    const loanDate = fieldValue(form, 'loanDate', account.accountType)
    const dueDate = fieldValue(form, 'dueDate', account.accountType)
    const validDay = (field: 'billingDay' | 'repaymentDay', label: string) => {
      const value = fieldValue(form, field, account.accountType)
      if (value !== null && !/^(?:[1-9]|[12]\d|3[01])$/.test(value)) fields[field] = `${label}应为 1 到 31`
    }

    if (interestRate !== null && !/^(?:0(?:\.\d{1,8})?|1(?:\.0{1,8})?)$/.test(interestRate)) {
      fields.interestRate = '年利率应为 0 到 1，最多 8 位小数'
    }
    if (currentAmountDue !== null && !amountPattern(account.currency).test(currentAmountDue)) {
      fields.currentAmountDue = `本期应还金额需符合 ${account.currency} 记账精度`
    }
    if (loanDate !== null && dueDate !== null && dueDate < loanDate) fields.dueDate = '到期日期不能早于借款日期'
    validDay('billingDay', '账单日')
    validDay('repaymentDay', '还款日')
    return { fields }
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const validation = validate()
    setErrors(validation)
    if (Object.keys(validation.fields).length > 0 || details === undefined) return
    mutation.mutate()
  }

  if (detailsQuery.isPending) {
    return (
      <Card aria-busy="true">
        <CardHeader><CardTitle>负债详情</CardTitle><CardDescription>正在加载负债提醒信息…</CardDescription></CardHeader>
      </Card>
    )
  }

  if (detailsQuery.isError || details === undefined) {
    return (
      <Card>
        <CardHeader><CardTitle>负债详情</CardTitle><CardDescription>无法加载负债提醒信息，请稍后重试。</CardDescription></CardHeader>
        <CardContent><Button variant="outline" onClick={() => void detailsQuery.refetch()}>重试</Button></CardContent>
      </Card>
    )
  }

  const isCreditCard = account.accountType === 'CREDIT_CARD'
  const supportsBillingDay = isApplicable(account.accountType, 'billingDay')
  const supportsLoanDates = isApplicable(account.accountType, 'loanDate')

  return (
    <Card>
      <CardHeader>
        <CardTitle>负债详情</CardTitle>
        <CardDescription>用于提醒；不改变账面余额、还款日期或已入账事实。</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error ? <p className="text-sm text-destructive" role="alert">{error}</p> : null}
        {editing ? (
          <form className="grid gap-4 md:grid-cols-2" onSubmit={handleSubmit} noValidate>
            <FormInput id="liability-interest-rate" label="年利率（年化比例）" value={form.interestRate} onChange={(value) => setValue('interestRate', value)}
              placeholder="例如：0.045" help="0.045 表示年化 4.5%。" error={errors.fields.interestRate} inputMode="decimal" />
            {supportsBillingDay ? (
              <FormInput id="liability-billing-day" label="账单日" value={form.billingDay} onChange={(value) => setValue('billingDay', value)}
                placeholder="1–31" help="短月按月末提醒。" error={errors.fields.billingDay} inputMode="numeric" />
            ) : null}
            {supportsLoanDates ? (
              <FormInput id="liability-loan-date" label="借款日期" value={form.loanDate} onChange={(value) => setValue('loanDate', value)}
                error={errors.fields.loanDate} type="date" />
            ) : null}
            {supportsLoanDates ? (
              <FormInput id="liability-due-date" label="到期日期" value={form.dueDate} onChange={(value) => setValue('dueDate', value)}
                error={errors.fields.dueDate} type="date" />
            ) : null}
            <FormInput id="liability-repayment-day" label="还款日" value={form.repaymentDay} onChange={(value) => setValue('repaymentDay', value)}
              placeholder="1–31" help="短月按月末提醒。" error={errors.fields.repaymentDay} inputMode="numeric" />
            <FormInput id="liability-current-amount-due" label="本期应还金额" value={form.currentAmountDue} onChange={(value) => setValue('currentAmountDue', value)}
              placeholder="0.00" help={`${account.currency}；仅用于提醒，不是账务余额。`} error={errors.fields.currentAmountDue} inputMode="decimal" />
            <div className="flex items-center gap-2 md:col-span-2">
              <Button type="submit" disabled={mutation.isPending}>
                {mutation.isPending ? <LoaderCircleIcon data-icon="inline-start" className="animate-spin" /> : null}
                保存负债详情
              </Button>
              <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => { setForm(formFromDetail(details)); setErrors({ fields: {} }); setError(null); setEditing(false) }}>取消</Button>
            </div>
          </form>
        ) : (
          <>
            <dl className="grid gap-3 text-sm md:grid-cols-2">
              <DetailValue label="年利率" value={details.interestRate === null ? null : `${details.interestRate}（年化比例）`} />
              {supportsLoanDates ? <DetailValue label="借款日期" value={details.loanDate} /> : null}
              {supportsLoanDates ? <DetailValue label="到期日期" value={details.dueDate} /> : null}
              {supportsBillingDay ? <DetailValue label="账单日" value={details.billingDay === null ? null : `${details.billingDay} 日`} /> : null}
              <DetailValue label="还款日" value={details.repaymentDay === null ? null : `${details.repaymentDay} 日`} />
              <DetailValue label="本期应还金额" value={details.currentAmountDue === null ? null : `${details.currentAmountDue} ${account.currency}（提醒）`} />
            </dl>
            {canWrite ? <Button variant="outline" onClick={() => { setForm(formFromDetail(details)); setEditing(true) }}>维护负债详情</Button> : null}
            {!canWrite && account.status === 'ACTIVE' ? <p className="text-xs text-muted-foreground">你拥有只读权限，不能维护负债详情。</p> : null}
            {isCreditCard ? <p className="text-xs text-muted-foreground">信用卡消费请在“记一笔”中选择支出和此信用卡账户。</p> : null}
          </>
        )}
      </CardContent>
    </Card>
  )
}

function FormInput({
  id,
  label,
  value,
  onChange,
  error,
  help,
  type = 'text',
  inputMode,
  placeholder,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
  help?: string
  type?: React.ComponentProps<typeof Input>['type']
  inputMode?: React.ComponentProps<typeof Input>['inputMode']
  placeholder?: string
}) {
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id}>{label}</label>
      <Input id={id} type={type} value={value} onChange={(event) => onChange(event.target.value)} inputMode={inputMode}
        placeholder={placeholder} aria-invalid={error ? true : undefined} />
      {help ? <p className="text-xs text-muted-foreground">{help}</p> : null}
      {error ? <p className="text-sm text-destructive">{error}</p> : null}
    </div>
  )
}

function DetailValue({ label, value }: { label: string; value: string | null }) {
  return <div className="flex flex-col gap-1"><dt className="text-muted-foreground">{label}</dt><dd>{value ?? '未设置'}</dd></div>
}

function toRequestBody(form: LiabilityDetailsForm, account: Account): PutLiabilityDetailBody {
  const currentAmountDue = fieldValue(form, 'currentAmountDue', account.accountType)
  const billingDay = fieldValue(form, 'billingDay', account.accountType)
  const repaymentDay = fieldValue(form, 'repaymentDay', account.accountType)
  return {
    interestRate: fieldValue(form, 'interestRate', account.accountType),
    loanDate: fieldValue(form, 'loanDate', account.accountType),
    dueDate: fieldValue(form, 'dueDate', account.accountType),
    billingDay: billingDay === null ? null : Number.parseInt(billingDay, 10),
    repaymentDay: repaymentDay === null ? null : Number.parseInt(repaymentDay, 10),
    currentAmountDue: currentAmountDue === null ? null : formatAmount(currentAmountDue, account.currency) as NonNegativePostedMoney,
  }
}
