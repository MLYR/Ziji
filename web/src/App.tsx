import { useQueryClient } from '@tanstack/react-query'
import { BarChart3Icon, DatabaseIcon, HomeIcon, LoaderCircleIcon, MoonIcon, ReceiptTextIcon, SunIcon, WalletCardsIcon } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'

import { AuthPage } from '@/auth/AuthPage'
import { AccountDetailPage } from '@/accounts/AccountDetailPage'
import { AccountsPage } from '@/accounts/AccountsPage'
import { CreateAccountPage } from '@/accounts/CreateAccountPage'
import { DashboardPage } from '@/dashboard/DashboardPage'
import { RecordTransactionPage } from '@/ledger/RecordTransactionPage'
import { TransactionDetailPage } from '@/ledger/TransactionDetailPage'
import { TransactionsPage } from '@/ledger/TransactionsPage'
import { CurrentSessionSignOutButton, DeviceSessionsSheet } from '@/auth/DeviceSessionsSheet'
import { retryWebSessionInitialization, useWebAuth } from '@/auth/auth-session'
import { useWebSessionInitialization } from '@/auth/use-web-session-initialization'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader, SidebarInset, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { TooltipProvider } from '@/components/ui/tooltip'
import { useUiStore } from '@/stores/ui-store'

const navigation = [
  { label: '概览', href: '/dashboard', icon: HomeIcon },
  { label: '账户', href: '/accounts', icon: WalletCardsIcon },
  { label: '流水', href: '/transactions', icon: ReceiptTextIcon },
  { label: '投资', href: '/investments', icon: BarChart3Icon },
]

function AppSidebar() {
  const { pathname } = useLocation()

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="p-4">
        <div className="flex items-center gap-3">
          <span className="grid size-8 place-items-center rounded-lg bg-primary font-bold text-primary-foreground" aria-hidden="true">Z</span>
          <div className="group-data-[collapsible=icon]:hidden">
            <p className="font-heading font-semibold">资迹</p>
            <p className="text-xs text-muted-foreground">ZIJI FINANCE</p>
          </div>
        </div>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>资产管理</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {navigation.map(({ label, href, icon: Icon }) => (
                <SidebarMenuItem key={href}>
                  <SidebarMenuButton asChild isActive={pathname === href} tooltip={label}>
                    {/* Access Token 只在内存；原生 a 会整页刷新并丢掉会话，必须走 React Router。 */}
                    <Link to={href}><Icon /><span>{label}</span></Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter className="p-4">
        <div className="flex items-center gap-2 text-xs text-muted-foreground"><DatabaseIcon />服务端为最终数据源</div>
      </SidebarFooter>
    </Sidebar>
  )
}

function PlaceholderPage({ title }: { title: string }) {
  return <main id="main-content" className="p-8"><h1 className="text-2xl font-semibold">{title}</h1><p className="mt-2 text-muted-foreground">页面将在对应业务任务开始时实现。</p></main>
}

function ProtectedShell() {
  const { accessToken, recoverWebSession, status, user } = useWebSessionInitialization()
  const { theme, toggleTheme } = useUiStore()

  if (status === 'unknown' || status === 'recovering') {
    return <main className="grid min-h-dvh place-items-center p-6" aria-busy="true"><Card className="w-full max-w-sm"><CardHeader><CardTitle>正在恢复会话</CardTitle><CardDescription>正在安全恢复登录状态，请稍候。</CardDescription></CardHeader><CardContent className="flex items-center gap-2 text-sm text-muted-foreground"><LoaderCircleIcon className="animate-spin" />正在验证设备会话…</CardContent></Card></main>
  }

  if (status === 'recovery_failed') {
    return <main className="grid min-h-dvh place-items-center p-6"><Card className="w-full max-w-sm"><CardHeader><CardTitle>无法恢复会话</CardTitle><CardDescription>网络或服务暂时不可用，尚未退出当前会话。</CardDescription></CardHeader><CardContent className="flex flex-col gap-3"><Button onClick={() => void retryWebSessionInitialization(recoverWebSession)}><LoaderCircleIcon data-icon="inline-start" />再次尝试</Button><Button variant="outline" asChild><a href="/login">前往登录</a></Button></CardContent></Card></main>
  }

  // 只有短期 Access Token 与服务端用户资料同时存在时才挂载业务壳，避免仅凭登录成功响应放行。
  if (status === 'unauthenticated' || !accessToken || !user) return <Navigate to="/login" replace />

  return (
    <SidebarProvider>
      <a href="#main-content" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:rounded-md focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-foreground">跳到主要内容</a>
      <AppSidebar />
      <SidebarInset>
        <header className="flex min-h-17 items-center justify-between border-b px-4 lg:px-6">
          <SidebarTrigger aria-label="切换侧栏" />
          <div className="flex items-center gap-2"><DeviceSessionsSheet /><CurrentSessionSignOutButton /><Button variant="outline" size="icon" onClick={toggleTheme} aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'}>
            {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
          </Button></div>
        </header>
        <Outlet />
      </SidebarInset>
    </SidebarProvider>
  )
}

function AuthQueryCacheBoundary() {
  const queryClient = useQueryClient()
  const { status, user } = useWebAuth()
  const previousUserId = useRef<string | null>(null)

  useEffect(() => {
    if (status === 'unauthenticated') {
      // 认证终态必须立即移除所有受保护查询，防止 A 退出后 B 看见 A 的缓存。
      queryClient.clear()
      previousUserId.current = null
      return
    }
    if (user && previousUserId.current !== null && previousUserId.current !== user.id) queryClient.clear()
    if (user) previousUserId.current = user.id
  }, [queryClient, status, user])

  return null
}

function App() {
  const { theme } = useUiStore()

  useEffect(() => {
    document.documentElement.classList.toggle('light', theme === 'light')
  }, [theme])

  return (
    <TooltipProvider>
      <AuthQueryCacheBoundary />
      {/* 公开认证页与受保护业务壳分离，避免未登录时渲染任何业务导航或占位数据。 */}
      <Routes>
        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />
        <Route path="/forgot-password" element={<AuthPage mode="reset" />} />
        <Route element={<ProtectedShell />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/accounts/new" element={<CreateAccountPage />} />
          <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
          <Route path="/transactions/new" element={<RecordTransactionPage />} />
          <Route path="/transactions/:transactionId" element={<TransactionDetailPage />} />
          <Route path="/investments" element={<PlaceholderPage title="投资" />} />
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </TooltipProvider>
  )
}

export default App
