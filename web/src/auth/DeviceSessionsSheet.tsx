import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { LaptopIcon, LoaderCircleIcon, LogOutIcon, ShieldAlertIcon } from 'lucide-react'
import { useState } from 'react'

import {
  listUserSessions,
  revokeAllUserSessions,
  revokeCurrentSession,
  revokeUserSession,
} from '@/auth/auth-api'
import { clearWebSession, useWebAuth } from '@/auth/auth-session'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiClientError } from '@/lib/api-client'

type PendingAction = { kind: 'current' | 'all' | 'selected'; sessionId?: string } | null

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function actionText(action: Exclude<PendingAction, null>) {
  if (action.kind === 'current') return '退出当前设备'
  if (action.kind === 'all') return '退出全部设备'
  return '撤销这台设备'
}

export function DeviceSessionsSheet() {
  const { session, user } = useWebAuth()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [pendingAction, setPendingAction] = useState<PendingAction>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const sessions = useInfiniteQuery({
    queryKey: ['device-sessions', user?.id],
    queryFn: ({ pageParam }) => listUserSessions(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.meta.hasMore ? page.meta.nextCursor ?? undefined : undefined,
    enabled: open && Boolean(user),
  })

  const allSessions = sessions.data?.pages.flatMap((page) => page.data) ?? []

  async function confirmAction() {
    if (!pendingAction) return

    setSubmitting(true)
    setActionError(null)
    try {
      if (pendingAction.kind === 'current') {
        await revokeCurrentSession()
        // 服务端成功撤销后再清理本地用户查询，避免网络失败被误报为退出成功。
        queryClient.clear()
        clearWebSession()
      } else if (pendingAction.kind === 'all') {
        await revokeAllUserSessions()
        queryClient.clear()
        clearWebSession()
      } else if (pendingAction.sessionId) {
        await revokeUserSession(pendingAction.sessionId)
        if (pendingAction.sessionId === session?.id) {
          queryClient.clear()
          clearWebSession()
        } else {
          await sessions.refetch()
        }
      }
      setPendingAction(null)
    } catch (error) {
      if (error instanceof ApiClientError && error.problem.status === 401) {
        queryClient.clear()
        clearWebSession()
        return
      }
      setActionError(error instanceof ApiClientError && error.problem.code === 'RESOURCE_NOT_FOUND'
        ? '该设备会话已经失效，请刷新列表后确认。'
        : '操作未完成，请检查网络或稍后重试。')
      setPendingAction(null)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button variant="outline"><LaptopIcon data-icon="inline-start" />设备与会话</Button>
      </SheetTrigger>
      <SheetContent className="w-full overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>设备与会话</SheetTitle>
          <SheetDescription>查看已登录设备，并撤销不再使用的会话。撤销后不可恢复。</SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-4 px-4 pb-4">
          {actionError ? <Alert variant="destructive"><ShieldAlertIcon /><AlertTitle>操作未完成</AlertTitle><AlertDescription>{actionError}</AlertDescription></Alert> : null}
          {sessions.isPending ? (
            <div className="flex flex-col gap-3" aria-label="正在加载设备会话">
              <Skeleton className="h-28 w-full" /><Skeleton className="h-28 w-full" />
            </div>
          ) : null}
          {sessions.isError ? <Alert variant="destructive"><ShieldAlertIcon /><AlertTitle>无法加载设备会话</AlertTitle><AlertDescription>请检查网络后重试。</AlertDescription></Alert> : null}
          {!sessions.isPending && !sessions.isError && allSessions.length === 0 ? (
            <Empty><EmptyHeader><EmptyMedia variant="icon"><LaptopIcon /></EmptyMedia><EmptyTitle>暂无可显示的设备会话</EmptyTitle><EmptyDescription>当前没有可管理的已登录设备。</EmptyDescription></EmptyHeader></Empty>
          ) : null}
          {allSessions.map((deviceSession) => {
            const current = deviceSession.id === session?.id
            return (
              <section key={deviceSession.id} className="flex flex-col gap-3 rounded-lg border p-4" aria-label={`${deviceSession.deviceName} 设备会话`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex min-w-0 flex-col gap-1">
                    <p className="truncate font-medium">{deviceSession.deviceName}</p>
                    {current ? <p className="text-sm text-muted-foreground">这是当前设备</p> : <p className="text-sm text-muted-foreground">历史设备会话</p>}
                  </div>
                  <Badge variant={deviceSession.status === 'ACTIVE' ? 'secondary' : 'outline'}>{deviceSession.status}</Badge>
                </div>
                <dl className="grid gap-1 text-sm text-muted-foreground">
                  <div className="flex justify-between gap-3"><dt>登录时间</dt><dd>{formatDateTime(deviceSession.createdAt)}</dd></div>
                  <div className="flex justify-between gap-3"><dt>最近活跃</dt><dd>{formatDateTime(deviceSession.lastSeenAt)}</dd></div>
                </dl>
                <Button variant="destructive" onClick={() => setPendingAction({ kind: current ? 'current' : 'selected', sessionId: deviceSession.id })}>
                  <LogOutIcon data-icon="inline-start" />{current ? '退出当前设备' : '撤销此设备'}
                </Button>
              </section>
            )
          })}
          {sessions.hasNextPage ? <Button variant="outline" disabled={sessions.isFetchingNextPage} onClick={() => void sessions.fetchNextPage()} aria-busy={sessions.isFetchingNextPage}>
            {sessions.isFetchingNextPage ? <LoaderCircleIcon className="animate-spin" data-icon="inline-start" /> : null}{sessions.isFetchingNextPage ? '正在加载…' : '加载更多设备'}
          </Button> : null}
          {allSessions.length > 0 ? <Button variant="destructive" onClick={() => setPendingAction({ kind: 'all' })}><LogOutIcon data-icon="inline-start" />退出全部设备</Button> : null}
        </div>
      </SheetContent>
      <AlertDialog open={pendingAction !== null} onOpenChange={(nextOpen) => { if (!nextOpen && !submitting) setPendingAction(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{pendingAction ? actionText(pendingAction) : '确认操作'}</AlertDialogTitle>
            <AlertDialogDescription>此安全操作不可撤销。确认后，被撤销设备需要重新登录才能继续使用。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={submitting}>取消</AlertDialogCancel>
            <AlertDialogAction variant="destructive" disabled={submitting} onClick={(event) => { event.preventDefault(); void confirmAction() }}>
              {submitting ? <LoaderCircleIcon className="animate-spin" data-icon="inline-start" /> : null}{submitting ? '正在处理…' : '确认撤销'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Sheet>
  )
}

export function CurrentSessionSignOutButton() {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function signOut() {
    setSubmitting(true)
    setError(null)
    try {
      await revokeCurrentSession()
      queryClient.clear()
      clearWebSession()
    } catch (caught) {
      if (caught instanceof ApiClientError && caught.problem.status === 401) {
        queryClient.clear()
        clearWebSession()
        return
      }
      // 网络和 5xx 不创建假的“已退出”状态，用户可留在当前会话继续重试。
      setError('退出未完成，请检查网络或稍后重试。')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={(nextOpen) => { if (!submitting) { setOpen(nextOpen); if (!nextOpen) setError(null) } }}>
      <AlertDialogTrigger asChild><Button variant="outline"><LogOutIcon data-icon="inline-start" />退出登录</Button></AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>退出当前设备</AlertDialogTitle>
          <AlertDialogDescription>退出后，此浏览器需要重新登录才能访问受保护数据。</AlertDialogDescription>
        </AlertDialogHeader>
        {error ? <Alert variant="destructive"><ShieldAlertIcon /><AlertTitle>退出未完成</AlertTitle><AlertDescription>{error}</AlertDescription></Alert> : null}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={submitting}>取消</AlertDialogCancel>
          <AlertDialogAction variant="destructive" disabled={submitting} onClick={(event) => { event.preventDefault(); void signOut() }}>
            {submitting ? <LoaderCircleIcon className="animate-spin" data-icon="inline-start" /> : null}{submitting ? '正在退出…' : '确认退出'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
