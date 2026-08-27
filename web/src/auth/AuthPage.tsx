import type { FormEvent, MutableRefObject, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  AlertCircleIcon,
  ArrowRightIcon,
  CheckCircle2Icon,
  EyeIcon,
  EyeOffIcon,
  KeyRoundIcon,
  LoaderCircleIcon,
  MailCheckIcon,
  MoonIcon,
  SparklesIcon,
  SunIcon,
} from 'lucide-react'

import Aurora from '@/components/Aurora'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { ApiClientError } from '@/lib/api-client'
import {
  createIdempotencyKey,
  createPasswordResetChallenge,
  createRegistrationChallenge,
  createWebSession,
  getCurrentUser,
  registerUser,
  resetPassword,
} from './auth-api'
import { clearWebSession, setWebSession, setWebUser, useWebAuth } from './auth-session'
import { cn } from '@/lib/utils'
import { useUiStore } from '@/stores/ui-store'

type AuthMode = 'login' | 'register' | 'reset'
type AuthFieldName =
  | 'email'
  | 'verificationCode'
  | 'challengeCode'
  | 'password'
  | 'newPassword'
  | 'nickname'
  | 'timezone'
  | 'baseCurrency'
  | 'locale'
  | 'confirmPassword'

type FieldErrors = Partial<Record<AuthFieldName, string>>
type FieldRefs = MutableRefObject<Partial<Record<AuthFieldName, HTMLInputElement | HTMLSelectElement | null>>>

const DEFAULT_TIMEZONE = 'Asia/Shanghai'
const DEFAULT_CURRENCY = 'CNY'
const DEFAULT_LOCALE = 'zh-CN'
const WEB_DEVICE_NAME = 'Web 浏览器'

const authFieldNames = new Set<AuthFieldName>([
  'email',
  'verificationCode',
  'challengeCode',
  'password',
  'newPassword',
  'nickname',
  'timezone',
  'baseCurrency',
  'locale',
])

function isValidEmail(email: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())
}

function getFieldErrors(error: unknown): FieldErrors {
  if (!(error instanceof ApiClientError)) return {}

  const entries = (error.problem.fieldErrors ?? []).flatMap(({ field, message }) => {
    if (!authFieldNames.has(field as AuthFieldName)) return []
    return [[field as AuthFieldName, message?.trim() || '请检查此字段。'] as const]
  })
  return Object.fromEntries(entries) as FieldErrors
}

function getAuthErrorMessage(error: unknown, action: string) {
  if (!(error instanceof ApiClientError)) return `${action}暂时无法完成，请检查网络后重试。`

  const { code, status } = error.problem
  if (code === 'INVALID_CREDENTIALS') return '邮箱或密码不正确，请检查后重试。'
  if (code === 'RATE_LIMITED' || status === 429) {
    return error.retryAfterSeconds
      ? `${action}请求过于频繁，请在 ${error.retryAfterSeconds} 秒后再试。`
      : `${action}请求过于频繁，请稍后再试。`
  }
  if (code === 'DUPLICATE_RESOURCE') return '该邮箱已注册，请直接登录或使用找回密码。'
  if (code === 'IDEMPOTENCY_KEY_REUSED') return '这次提交已失效，请重新填写后再试。'
  if (code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS') {
    return error.retryAfterSeconds
      ? `上一次请求仍在处理中，请等待 ${error.retryAfterSeconds} 秒后再试。`
      : '上一次请求仍在处理中，请稍后再试。'
  }
  if (code === 'VALIDATION_ERROR' || status === 400) return '请检查标记的字段后重试。'
  if (code === 'INTERNAL_ERROR' || status >= 500) return `${action}暂时无法完成，请稍后再试。`
  return `${action}未完成，请稍后再试。`
}

function focusFirstError(fieldErrors: FieldErrors, refs: FieldRefs, fallback?: AuthFieldName) {
  const field = Object.keys(fieldErrors)[0] as AuthFieldName | undefined
  const target = refs.current[field ?? fallback ?? 'email']
  window.setTimeout(() => target?.focus(), 0)
}

function challengeExpiryText(expiresIn: number | null) {
  if (!expiresIn) return '验证码已发送，请在有效期内完成验证。'
  return `验证码已发送，有效期约 ${Math.max(1, Math.ceil(expiresIn / 60))} 分钟。`
}

function AuthField({
  children,
  error,
  help,
  id,
  label,
  required = true,
}: {
  children: ReactNode
  error?: string
  help?: string
  id: string
  label: string
  required?: boolean
}) {
  return (
    <div className="grid gap-2">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {required ? <><span aria-hidden="true" className="ml-1 text-primary">*</span><span className="sr-only">必填</span></> : null}
      </label>
      {children}
      {help ? <p id={`${id}-help`} className="text-xs leading-5 text-muted-foreground">{help}</p> : null}
      {error ? <p id={`${id}-error`} className="flex items-start gap-1.5 text-sm text-destructive" role="alert"><AlertCircleIcon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />{error}</p> : null}
    </div>
  )
}

function PasswordField({
  autoComplete,
  error,
  id,
  inputRef,
  label,
  onChange,
  value,
}: {
  autoComplete: string
  error?: string
  id: string
  inputRef?: (element: HTMLInputElement | null) => void
  label: string
  onChange: (value: string) => void
  value: string
}) {
  const [visible, setVisible] = useState(false)

  return (
    <AuthField id={id} label={label} error={error}>
      <div className="relative">
        <Input
          ref={inputRef}
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete={autoComplete}
          required
          aria-invalid={Boolean(error)}
          aria-describedby={error ? `${id}-error` : undefined}
          className="h-11 pr-12 text-base"
        />
        <button
          type="button"
          className="absolute inset-y-0 right-0 grid size-11 place-items-center rounded-r-lg text-muted-foreground transition-colors hover:text-foreground"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? `隐藏${label}` : `显示${label}`}
        >
          {visible ? <EyeOffIcon aria-hidden="true" /> : <EyeIcon aria-hidden="true" />}
        </button>
      </div>
    </AuthField>
  )
}

function FormAlert({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <Alert variant="destructive" aria-live="assertive">
      <AlertCircleIcon aria-hidden="true" />
      <AlertTitle>操作未完成</AlertTitle>
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  )
}

function LoadingButton({ children, loading }: { children: ReactNode; loading: boolean }) {
  return (
    <Button type="submit" disabled={loading} aria-busy={loading} className="h-11 w-full gap-2">
      {loading ? <LoaderCircleIcon className="animate-spin" aria-hidden="true" /> : null}
      {children}
    </Button>
  )
}

function AuthModeNav({ mode }: { mode: AuthMode }) {
  return (
    <nav aria-label="认证方式" className="mb-7 grid grid-cols-2 gap-1 rounded-lg border border-border bg-muted/50 p-1">
      {[
        ['login', '登录', '/login'],
        ['register', '邮箱注册', '/register'],
      ].map(([key, label, href]) => (
        <Link
          key={key}
          to={href}
          className={cn(
            'rounded-md px-3 py-2.5 text-center text-sm font-medium transition-colors',
            mode === key ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:bg-background/70 hover:text-foreground',
          )}
          aria-current={mode === key ? 'page' : undefined}
        >
          {label}
        </Link>
      ))}
    </nav>
  )
}

function AuthLayout({ children, description, title }: { children: ReactNode; description: string; title: string }) {
  const { theme, toggleTheme } = useUiStore()

  return (
    <div className="relative min-h-dvh overflow-hidden bg-background">
      <div className="pointer-events-none absolute inset-0 lg:inset-y-0 lg:right-1/2">
        <Aurora className="size-full opacity-75" colorStops={['#ff8e3c', '#d9376e', '#5f3dc4']} amplitude={0.8} blend={0.5} speed={0.35} />
      </div>
      <div className="relative mx-auto grid min-h-dvh max-w-[1440px] lg:grid-cols-[minmax(360px,0.9fr)_minmax(480px,1.1fr)]">
        <section className="relative hidden flex-col justify-between border-r border-border bg-card/75 p-8 lg:flex xl:p-12">
          <div className="flex items-center gap-3">
            <span className="grid size-9 place-items-center rounded-lg bg-primary font-heading text-lg font-bold text-primary-foreground" aria-hidden="true">Z</span>
            <div>
              <p className="font-heading text-base font-semibold">资迹</p>
              <p className="text-[10px] tracking-[0.24em] text-muted-foreground">ZIJI FINANCE</p>
            </div>
          </div>
          <div className="max-w-xl py-16">
            <Badge variant="outline" className="mb-5 gap-1.5"><SparklesIcon aria-hidden="true" />清晰管理每一笔钱</Badge>
            <h2 className="font-heading text-4xl font-semibold leading-tight tracking-tight xl:text-5xl">看清每一笔钱，<br />知道它在哪里。</h2>
            <p className="mt-5 max-w-[42ch] text-base leading-7 text-muted-foreground">统一管理现金、负债、投资和家庭共享账户，让资产变化有迹可循。</p>
          </div>
          <div className="grid grid-cols-3 overflow-hidden rounded-xl border border-border bg-background/60">
            {[
              ['本地优先', '移动端支持离线记账'],
              ['可追溯', '修改保留审计历史'],
              ['服务端权威', '数据以事实为准'],
            ].map(([name, detail], index) => (
              <div key={name} className={cn('p-4', index > 0 && 'border-l border-border')}>
                <strong className="block font-mono text-sm">{name}</strong>
                <span className="mt-1 block text-xs leading-5 text-muted-foreground">{detail}</span>
              </div>
            ))}
          </div>
        </section>

        <main id="main-content" className="relative flex min-h-dvh items-center justify-center px-5 py-8 sm:px-8 lg:px-12">
          <div className="w-full max-w-md">
            <div className="mb-4 flex items-center justify-between lg:justify-end">
              <Link to="/login" className="flex items-center gap-2 lg:hidden">
                <span className="grid size-8 place-items-center rounded-lg bg-primary font-heading font-bold text-primary-foreground" aria-hidden="true">Z</span>
                <span className="font-heading font-semibold">资迹</span>
              </Link>
              <Button variant="ghost" size="icon" onClick={toggleTheme} aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'} className="size-11">
                {theme === 'dark' ? <SunIcon aria-hidden="true" /> : <MoonIcon aria-hidden="true" />}
              </Button>
            </div>
            <Card className="border-border/80 bg-card/90 shadow-2xl shadow-black/20 backdrop-blur-sm">
              <CardHeader className="gap-3 p-6 pb-2 sm:p-8 sm:pb-3">
                <p className="text-xs font-medium uppercase tracking-[0.22em] text-primary">ZIJI / ACCOUNT</p>
                {/* 使用真实标题元素，让认证页在键盘和读屏流程中有明确的页面起点。 */}
                <h1 className="font-heading text-2xl tracking-tight sm:text-3xl">{title}</h1>
                <CardDescription className="text-sm leading-6">{description}</CardDescription>
              </CardHeader>
              <CardContent className="p-6 pt-5 sm:p-8 sm:pt-6">{children}</CardContent>
            </Card>
            <p className="mt-5 text-center text-xs leading-5 text-muted-foreground">你的密码只用于认证，资迹不会在客户端保存明文凭据。</p>
          </div>
        </main>
      </div>
    </div>
  )
}

function LoginForm() {
  const navigate = useNavigate()
  const refs = useRef<Partial<Record<AuthFieldName, HTMLInputElement | null>>>({})
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: FieldErrors = {}
    if (!isValidEmail(email)) nextErrors.email = '请输入有效的邮箱地址。'
    if (!password) nextErrors.password = '请输入密码。'
    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors)
      setError('请检查标记的字段后重试。')
      focusFirstError(nextErrors, refs, 'email')
      return
    }

    setLoading(true)
    setError(null)
    setFieldErrors({})
    try {
      const session = await createWebSession({ email: email.trim(), password, deviceName: WEB_DEVICE_NAME })
      if (!session.data?.accessToken) throw new Error('登录响应无效')
      setWebSession(session.data)
      const profile = await getCurrentUser()
      setWebUser(profile.data)
      navigate('/dashboard', { replace: true })
    } catch (caught) {
      clearWebSession()
      const nextServerErrors = getFieldErrors(caught)
      setFieldErrors(nextServerErrors)
      setError(getAuthErrorMessage(caught, '登录'))
      focusFirstError(nextServerErrors, refs, 'email')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <AuthModeNav mode="login" />
      <form onSubmit={submit} noValidate aria-busy={loading} className="grid gap-5">
        <FormAlert message={error} />
        <AuthField id="login-email" label="邮箱地址" error={fieldErrors.email}>
          <Input
            ref={(element) => { refs.current.email = element }}
            id="login-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            inputMode="email"
            required
            aria-invalid={Boolean(fieldErrors.email)}
            aria-describedby={fieldErrors.email ? 'login-email-error' : undefined}
            className="h-11 text-base"
          />
        </AuthField>
        <PasswordField
          id="login-password"
          label="密码"
          value={password}
          onChange={setPassword}
          autoComplete="current-password"
          error={fieldErrors.password}
          inputRef={(element) => { refs.current.password = element }}
        />
        <div className="-mt-2 flex justify-end">
          <Link to="/forgot-password" className="text-sm text-primary underline-offset-4 hover:underline">忘记密码？</Link>
        </div>
        <LoadingButton loading={loading}>{loading ? '登录中…' : '登录'}</LoadingButton>
        <p className="text-center text-sm text-muted-foreground">还没有账户？ <Link to="/register" className="font-medium text-primary underline-offset-4 hover:underline">邮箱注册</Link></p>
      </form>
    </>
  )
}

function RegistrationForm() {
  const refs = useRef<Partial<Record<AuthFieldName, HTMLInputElement | HTMLSelectElement | null>>>({})
  const [email, setEmail] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [timezone, setTimezone] = useState(DEFAULT_TIMEZONE)
  const [baseCurrency, setBaseCurrency] = useState<'CNY' | 'USD' | 'HKD' | 'JPY' | 'EUR'>(DEFAULT_CURRENCY)
  const [locale, setLocale] = useState(DEFAULT_LOCALE)
  const [challengeSent, setChallengeSent] = useState(false)
  const [expiresIn, setExpiresIn] = useState<number | null>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)

  async function sendCode() {
    const nextErrors: FieldErrors = {}
    if (!isValidEmail(email)) nextErrors.email = '请输入有效的邮箱地址。'
    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors)
      setError('请先填写有效邮箱，再发送验证码。')
      focusFirstError(nextErrors, refs, 'email')
      return
    }

    setSending(true)
    setError(null)
    setFieldErrors({})
    try {
      const result = await createRegistrationChallenge({ email: email.trim() })
      setChallengeSent(true)
      setExpiresIn(result.data.expiresIn)
      setVerificationCode('')
    } catch (caught) {
      const nextServerErrors = getFieldErrors(caught)
      setFieldErrors(nextServerErrors)
      setError(getAuthErrorMessage(caught, '验证码发送'))
      focusFirstError(nextServerErrors, refs, 'email')
    } finally {
      setSending(false)
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: FieldErrors = {}
    if (!isValidEmail(email)) nextErrors.email = '请输入有效的邮箱地址。'
    if (!challengeSent) setError('请先发送邮箱验证码。')
    if (!/^[0-9]{6}$/.test(verificationCode)) nextErrors.verificationCode = '请输入 6 位数字验证码。'
    if (password.length < 10) nextErrors.password = '密码至少需要 10 个字符。'
    if (password !== confirmPassword) nextErrors.confirmPassword = '两次输入的密码不一致。'
    if (!nickname.trim()) nextErrors.nickname = '请输入昵称。'
    if (Object.keys(nextErrors).length > 0 || !challengeSent) {
      setFieldErrors(nextErrors)
      setError(!challengeSent ? '请先发送邮箱验证码。' : '请检查标记的字段后重试。')
      focusFirstError(nextErrors, refs, !challengeSent ? 'email' : 'verificationCode')
      return
    }

    setLoading(true)
    setError(null)
    setFieldErrors({})
    try {
      const result = await registerUser({
        email: email.trim(),
        verificationCode,
        password,
        nickname: nickname.trim(),
        timezone,
        baseCurrency,
        locale,
      }, createIdempotencyKey())
      setSuccess(`欢迎加入资迹，${result.data.nickname} 的账户已经创建。`)
      setPassword('')
      setConfirmPassword('')
      setVerificationCode('')
    } catch (caught) {
      const nextServerErrors = getFieldErrors(caught)
      setFieldErrors(nextServerErrors)
      setError(getAuthErrorMessage(caught, '注册'))
      focusFirstError(nextServerErrors, refs, 'verificationCode')
    } finally {
      setLoading(false)
    }
  }

  if (success) {
    return <SuccessPanel title="注册完成" message={success} linkLabel="返回登录" linkTo="/login" />
  }

  return (
    <>
      <AuthModeNav mode="register" />
      <form onSubmit={submit} noValidate aria-busy={loading} className="grid gap-5">
        <FormAlert message={error} />
        <AuthField id="register-email" label="邮箱地址" error={fieldErrors.email}>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              ref={(element) => { refs.current.email = element }}
              id="register-email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="email"
              inputMode="email"
              required
              aria-invalid={Boolean(fieldErrors.email)}
              aria-describedby={fieldErrors.email ? 'register-email-error' : undefined}
              className="h-11 min-w-0 flex-1 text-base"
            />
            <Button type="button" variant="outline" disabled={sending || loading} onClick={sendCode} className="h-11 shrink-0 gap-2 sm:w-32">
              {sending ? <LoaderCircleIcon className="animate-spin" aria-hidden="true" /> : <MailCheckIcon aria-hidden="true" />}
              {sending ? '发送中…' : '发送验证码'}
            </Button>
          </div>
        </AuthField>
        {challengeSent ? <p className="-mt-2 flex items-center gap-2 text-sm text-primary" role="status" aria-live="polite"><CheckCircle2Icon aria-hidden="true" />{challengeExpiryText(expiresIn)}</p> : null}
        <AuthField id="register-code" label="邮箱验证码" error={fieldErrors.verificationCode} help="验证码为 6 位数字，仅在短时间内有效。">
          <Input
            ref={(element) => { refs.current.verificationCode = element }}
            id="register-code"
            value={verificationCode}
            onChange={(event) => setVerificationCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength={6}
            required
            aria-invalid={Boolean(fieldErrors.verificationCode)}
            aria-describedby={['register-code-help', fieldErrors.verificationCode ? 'register-code-error' : ''].filter(Boolean).join(' ') || undefined}
            className="h-11 text-base tracking-[0.28em]"
          />
        </AuthField>
        <div className="grid gap-5 sm:grid-cols-2">
          <PasswordField id="register-password" label="设置密码" value={password} onChange={setPassword} autoComplete="new-password" error={fieldErrors.password} inputRef={(element) => { refs.current.password = element }} />
          <AuthField id="register-confirm-password" label="确认密码" error={fieldErrors.confirmPassword}>
            <Input
              ref={(element) => { refs.current.confirmPassword = element }}
              id="register-confirm-password"
              type="password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              autoComplete="new-password"
              required
              aria-invalid={Boolean(fieldErrors.confirmPassword)}
              aria-describedby={fieldErrors.confirmPassword ? 'register-confirm-password-error' : undefined}
              className="h-11 text-base"
            />
          </AuthField>
        </div>
        <AuthField id="register-nickname" label="昵称" error={fieldErrors.nickname}>
          <Input
            ref={(element) => { refs.current.nickname = element }}
            id="register-nickname"
            value={nickname}
            onChange={(event) => setNickname(event.target.value)}
            autoComplete="nickname"
            required
            aria-invalid={Boolean(fieldErrors.nickname)}
            aria-describedby={fieldErrors.nickname ? 'register-nickname-error' : undefined}
            className="h-11 text-base"
          />
        </AuthField>
        <fieldset className="grid gap-3 rounded-lg border border-border/70 bg-muted/30 p-4">
          <legend className="px-1 text-sm font-medium">偏好设置</legend>
          <div className="grid gap-4 sm:grid-cols-3">
            <AuthField id="register-timezone" label="时区" error={fieldErrors.timezone}>
              <select ref={(element) => { refs.current.timezone = element }} id="register-timezone" value={timezone} onChange={(event) => setTimezone(event.target.value)} className="h-11 w-full rounded-lg border border-input bg-background px-3 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50" aria-invalid={Boolean(fieldErrors.timezone)} aria-describedby={fieldErrors.timezone ? 'register-timezone-error' : undefined}>
                <option value="Asia/Shanghai">上海 UTC+8</option>
                <option value="Asia/Hong_Kong">香港 UTC+8</option>
                <option value="UTC">UTC</option>
              </select>
            </AuthField>
            <AuthField id="register-currency" label="基准币种" error={fieldErrors.baseCurrency}>
              <select ref={(element) => { refs.current.baseCurrency = element }} id="register-currency" value={baseCurrency} onChange={(event) => setBaseCurrency(event.target.value as typeof baseCurrency)} className="h-11 w-full rounded-lg border border-input bg-background px-3 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50" aria-invalid={Boolean(fieldErrors.baseCurrency)} aria-describedby={fieldErrors.baseCurrency ? 'register-currency-error' : undefined}>
                <option value="CNY">人民币 CNY</option>
                <option value="USD">美元 USD</option>
                <option value="HKD">港币 HKD</option>
                <option value="JPY">日元 JPY</option>
                <option value="EUR">欧元 EUR</option>
              </select>
            </AuthField>
            <AuthField id="register-locale" label="语言" error={fieldErrors.locale}>
              <select ref={(element) => { refs.current.locale = element }} id="register-locale" value={locale} onChange={(event) => setLocale(event.target.value)} className="h-11 w-full rounded-lg border border-input bg-background px-3 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50" aria-invalid={Boolean(fieldErrors.locale)} aria-describedby={fieldErrors.locale ? 'register-locale-error' : undefined}>
                <option value="zh-CN">简体中文</option>
                <option value="en-US">English</option>
              </select>
            </AuthField>
          </div>
        </fieldset>
        <LoadingButton loading={loading}>{loading ? '创建中…' : '创建账户'}</LoadingButton>
        <p className="text-center text-sm text-muted-foreground">已有账户？ <Link to="/login" className="font-medium text-primary underline-offset-4 hover:underline">返回登录</Link></p>
      </form>
    </>
  )
}

function ResetForm() {
  const refs = useRef<Partial<Record<AuthFieldName, HTMLInputElement | null>>>({})
  const [email, setEmail] = useState('')
  const [challengeCode, setChallengeCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [challengeSent, setChallengeSent] = useState(false)
  const [expiresIn, setExpiresIn] = useState<number | null>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)

  async function sendCode() {
    const nextErrors: FieldErrors = {}
    if (!isValidEmail(email)) nextErrors.email = '请输入有效的邮箱地址。'
    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors)
      setError('请先填写有效邮箱，再发送验证码。')
      focusFirstError(nextErrors, refs, 'email')
      return
    }

    setSending(true)
    setError(null)
    setFieldErrors({})
    try {
      const result = await createPasswordResetChallenge({ email: email.trim() })
      setChallengeSent(true)
      setExpiresIn(result.data.expiresIn)
      setChallengeCode('')
    } catch (caught) {
      const nextServerErrors = getFieldErrors(caught)
      setFieldErrors(nextServerErrors)
      setError(getAuthErrorMessage(caught, '验证码发送'))
      focusFirstError(nextServerErrors, refs, 'email')
    } finally {
      setSending(false)
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: FieldErrors = {}
    if (!isValidEmail(email)) nextErrors.email = '请输入有效的邮箱地址。'
    if (!/^[0-9]{6}$/.test(challengeCode)) nextErrors.challengeCode = '请输入 6 位数字验证码。'
    if (newPassword.length < 10) nextErrors.newPassword = '新密码至少需要 10 个字符。'
    if (newPassword !== confirmPassword) nextErrors.confirmPassword = '两次输入的密码不一致。'
    if (Object.keys(nextErrors).length > 0 || !challengeSent) {
      setFieldErrors(nextErrors)
      setError(!challengeSent ? '请先发送邮箱验证码。' : '请检查标记的字段后重试。')
      focusFirstError(nextErrors, refs, !challengeSent ? 'email' : 'challengeCode')
      return
    }

    setLoading(true)
    setError(null)
    setFieldErrors({})
    try {
      await resetPassword({ email: email.trim(), challengeCode, newPassword }, createIdempotencyKey())
      setSuccess(true)
      setChallengeCode('')
      setNewPassword('')
      setConfirmPassword('')
    } catch (caught) {
      const nextServerErrors = getFieldErrors(caught)
      setFieldErrors(nextServerErrors)
      setError(getAuthErrorMessage(caught, '密码重置'))
      focusFirstError(nextServerErrors, refs, 'challengeCode')
    } finally {
      setLoading(false)
    }
  }

  if (success) return <SuccessPanel title="密码已重置" message="请使用新密码登录。为了保护账户，已有设备会话已按安全策略处理。" linkLabel="返回登录" linkTo="/login" />

  return (
    <form onSubmit={submit} noValidate aria-busy={loading} className="grid gap-5">
      <div className="mb-1 flex items-start gap-3 rounded-lg border border-border bg-muted/30 p-3.5 text-sm leading-6 text-muted-foreground">
        <KeyRoundIcon className="mt-1 size-4 shrink-0 text-primary" aria-hidden="true" />
        <p>我们不会根据邮箱是否注册来改变发送结果。请填写你记得的邮箱并完成验证。</p>
      </div>
      <FormAlert message={error} />
      <AuthField id="reset-email" label="邮箱地址" error={fieldErrors.email}>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Input
            ref={(element) => { refs.current.email = element }}
            id="reset-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            inputMode="email"
            required
            aria-invalid={Boolean(fieldErrors.email)}
            aria-describedby={fieldErrors.email ? 'reset-email-error' : undefined}
            className="h-11 min-w-0 flex-1 text-base"
          />
          <Button type="button" variant="outline" disabled={sending || loading} onClick={sendCode} className="h-11 shrink-0 gap-2 sm:w-32">
            {sending ? <LoaderCircleIcon className="animate-spin" aria-hidden="true" /> : <MailCheckIcon aria-hidden="true" />}
            {sending ? '发送中…' : '发送验证码'}
          </Button>
        </div>
      </AuthField>
      {challengeSent ? <p className="-mt-2 flex items-center gap-2 text-sm text-primary" role="status" aria-live="polite"><CheckCircle2Icon aria-hidden="true" />{challengeExpiryText(expiresIn)}</p> : null}
      <AuthField id="reset-code" label="验证码" error={fieldErrors.challengeCode}>
        <Input
          ref={(element) => { refs.current.challengeCode = element }}
          id="reset-code"
          value={challengeCode}
          onChange={(event) => setChallengeCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
          autoComplete="one-time-code"
          inputMode="numeric"
          maxLength={6}
          required
          aria-invalid={Boolean(fieldErrors.challengeCode)}
          aria-describedby={fieldErrors.challengeCode ? 'reset-code-error' : undefined}
          className="h-11 text-base tracking-[0.28em]"
        />
      </AuthField>
      <PasswordField id="reset-new-password" label="新密码" value={newPassword} onChange={setNewPassword} autoComplete="new-password" error={fieldErrors.newPassword} inputRef={(element) => { refs.current.newPassword = element }} />
      <AuthField id="reset-confirm-password" label="确认新密码" error={fieldErrors.confirmPassword}>
        <Input
          ref={(element) => { refs.current.confirmPassword = element }}
          id="reset-confirm-password"
          type="password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          autoComplete="new-password"
          required
          aria-invalid={Boolean(fieldErrors.confirmPassword)}
          aria-describedby={fieldErrors.confirmPassword ? 'reset-confirm-password-error' : undefined}
          className="h-11 text-base"
        />
      </AuthField>
      <LoadingButton loading={loading}>{loading ? '重置中…' : '重置密码'}</LoadingButton>
      <p className="text-center text-sm text-muted-foreground"><Link to="/login" className="font-medium text-primary underline-offset-4 hover:underline">返回登录</Link></p>
    </form>
  )
}

function SuccessPanel({ linkLabel, linkTo, message, title }: { linkLabel: string; linkTo: string; message: string; title: string }) {
  return (
    <div className="grid gap-5 text-center">
      <div className="mx-auto grid size-14 place-items-center rounded-full border border-primary/30 bg-primary/10 text-primary"><CheckCircle2Icon className="size-7" aria-hidden="true" /></div>
      <div className="grid gap-2">
        <h2 className="font-heading text-xl font-semibold">{title}</h2>
        <p className="text-sm leading-6 text-muted-foreground">{message}</p>
      </div>
      <Button asChild className="h-11 w-full gap-2"><Link to={linkTo}>{linkLabel}<ArrowRightIcon aria-hidden="true" /></Link></Button>
    </div>
  )
}

export function AuthPage({ mode }: { mode: AuthMode }) {
  const navigate = useNavigate()
  const { user } = useWebAuth()

  useEffect(() => {
    if (user) navigate('/dashboard', { replace: true })
  }, [navigate, user])

  if (mode === 'register') {
    return <AuthLayout title="创建资迹账户" description="使用邮箱验证码注册，不需要手机号。"><RegistrationForm /></AuthLayout>
  }
  if (mode === 'reset') {
    return <AuthLayout title="找回密码" description="输入注册邮箱，我们会发送一次性密码重置验证码。"><ResetForm /></AuthLayout>
  }
  return <AuthLayout title="欢迎回来" description="登录后继续查看你的资金全貌。"><LoginForm /></AuthLayout>
}
