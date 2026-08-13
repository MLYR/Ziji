import type { paths } from '@ziji/api-types'
import { AlertCircleIcon, BarChart3Icon, DatabaseIcon, HomeIcon, MoonIcon, ReceiptTextIcon, SunIcon, WalletCardsIcon } from 'lucide-react'
import { useEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader, SidebarInset, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { Skeleton } from '@/components/ui/skeleton'
import { TooltipProvider } from '@/components/ui/tooltip'
import { MotionGroup } from '@/motion/motion-group'
import { useUiStore } from '@/stores/ui-store'

type DashboardResponse = paths['/dashboard']['get']['responses'][200]

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
                    <a href={href}><Icon /><span>{label}</span></a>
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

function DashboardPage() {
  // 此类型断言只证明共享契约已接入，业务请求将在认证基础设施就绪后实现。
  const contractStatus: DashboardResponse['content'] | undefined = undefined
  void contractStatus

  return (
    <main id="main-content" className="flex flex-col gap-6 p-6 lg:p-8">
      <MotionGroup className="contents">
        <section className="flex flex-wrap items-end justify-between gap-4" data-motion-item>
          <div className="flex flex-col gap-1">
            <Badge variant="outline">工程基线</Badge>
            <h1 className="font-heading text-2xl font-semibold tracking-tight">总览基础设施已就绪</h1>
            <p className="text-sm text-muted-foreground">正式财务数据将在 B1 业务接口完成后接入。</p>
          </div>
          <Button>新增一笔</Button>
        </section>
        <section className="grid gap-4 md:grid-cols-3" aria-label="资产指标加载示例" data-motion-item>
          {['净资产', '总资产', '总负债'].map((label) => (
            <Card key={label}>
              <CardHeader><CardTitle>{label}</CardTitle><CardDescription>等待服务端数据</CardDescription></CardHeader>
              <CardContent><Skeleton className="h-8 w-2/3" /></CardContent>
            </Card>
          ))}
        </section>
        <Card data-motion-item>
          <CardHeader>
            <CardTitle>状态边界</CardTitle>
            <CardDescription>查询、路由、主题与契约基础设施均已连接。</CardDescription>
            <CardAction><Badge variant="secondary">READY</Badge></CardAction>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Alert>
              <AlertCircleIcon />
              <AlertTitle>尚无业务数据</AlertTitle>
              <AlertDescription>这不是零余额；在账户和账务模块实现前，页面保持明确的未加载状态。</AlertDescription>
            </Alert>
            <Separator />
            <p className="text-sm text-muted-foreground">ECharts 已安装为正式图表引擎，当前不使用假数据绘制资产走势。</p>
          </CardContent>
        </Card>
      </MotionGroup>
    </main>
  )
}

function PlaceholderPage({ title }: { title: string }) {
  return <main id="main-content" className="p-8"><h1 className="text-2xl font-semibold">{title}</h1><p className="mt-2 text-muted-foreground">页面将在对应业务任务开始时实现。</p></main>
}

function App() {
  const { theme, toggleTheme } = useUiStore()

  useEffect(() => {
    document.documentElement.classList.toggle('light', theme === 'light')
  }, [theme])

  return (
    <TooltipProvider>
      <SidebarProvider>
        <a href="#main-content" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:rounded-md focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-foreground">跳到主要内容</a>
        <AppSidebar />
        <SidebarInset>
          <header className="flex min-h-17 items-center justify-between border-b px-4 lg:px-6">
            <SidebarTrigger aria-label="切换侧栏" />
            <Button variant="outline" size="icon" onClick={toggleTheme} aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'}>
              {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
            </Button>
          </header>
          <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/accounts" element={<PlaceholderPage title="账户" />} />
            <Route path="/transactions" element={<PlaceholderPage title="流水" />} />
            <Route path="/investments" element={<PlaceholderPage title="投资" />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </SidebarInset>
      </SidebarProvider>
    </TooltipProvider>
  )
}

export default App
