import { useEffect } from 'react'

import { getCurrentUser, refreshWebSession } from '@/auth/auth-api'
import {
  clearWebSession,
  initializeWebSession,
  markWebSessionRecoveryFailed,
  setWebSession,
  setWebUser,
  useWebAuth,
} from '@/auth/auth-session'
import { ApiClientError } from '@/lib/api-client'

async function recoverWebSession() {
  try {
    const refreshed = await refreshWebSession()
    setWebSession(refreshed.data)
    // 已经轮换成功后，主体确认不能再触发第二次刷新，避免无意义的连续轮换。
    const profile = await getCurrentUser(false)
    setWebUser(profile.data)
  } catch (error) {
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
