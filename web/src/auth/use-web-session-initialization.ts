import { useEffect } from 'react'

import { getCurrentUser, refreshWebSession } from '@/auth/auth-api'
import {
  clearWebSession,
  getWebAuthSnapshot,
  initializeWebSession,
  markWebSessionRecoveryFailed,
  setWebUser,
  useWebAuth,
} from '@/auth/auth-session'
import { ApiClientError, getWebSessionGeneration } from '@/lib/api-client'

async function recoverWebSession() {
  const recoveryGeneration = getWebSessionGeneration()
  let recoverySessionId = getWebAuthSnapshot().session?.id ?? null
  try {
    if (await refreshWebSession() === null) return
    recoverySessionId = getWebAuthSnapshot().session?.id ?? null
    // 已经轮换成功后，主体确认不能再触发第二次刷新，避免无意义的连续轮换。
    const profile = await getCurrentUser(false)
    if (getWebSessionGeneration() !== recoveryGeneration || (getWebAuthSnapshot().session?.id ?? null) !== recoverySessionId) return
    setWebUser(profile.data)
  } catch (error) {
    // 旧 profile 请求不能覆盖或清理已经切换到新主体的会话。
    if (getWebSessionGeneration() !== recoveryGeneration || (getWebAuthSnapshot().session?.id ?? null) !== recoverySessionId) return
    if (error instanceof ApiClientError && (error.problem.status === 401 || error.problem.status === 403)) {
      clearWebSession()
      return
    }
    markWebSessionRecoveryFailed()
  }
}

export function useWebSessionInitialization() {
  const auth = useWebAuth()

  useEffect(() => {
    if (auth.status === 'unknown') void initializeWebSession(recoverWebSession)
  }, [auth.status])

  return { ...auth, recoverWebSession }
}
