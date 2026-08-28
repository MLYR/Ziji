import type { components } from '@ziji/api-types'
import { useSyncExternalStore } from 'react'

type Session = components['schemas']['Session']
type User = components['schemas']['User']
type WebSessionData = components['schemas']['WebSessionEnvelope']['data']

export interface WebAuthSnapshot {
  accessToken: string | null
  session: Session | null
  user: User | null
  status: 'unknown' | 'recovering' | 'authenticated' | 'unauthenticated' | 'recovery_failed'
}

const INITIAL_SNAPSHOT: WebAuthSnapshot = { accessToken: null, session: null, user: null, status: 'unknown' }
const EMPTY_SNAPSHOT: WebAuthSnapshot = { accessToken: null, session: null, user: null, status: 'unauthenticated' }

let snapshot = INITIAL_SNAPSHOT
const listeners = new Set<() => void>()
let initializationPromise: Promise<void> | null = null

function publish(next: WebAuthSnapshot) {
  snapshot = next
  listeners.forEach((listener) => listener())
}

export function setWebSession(data: WebSessionData) {
  // Web Access Token 仅驻留内存；刷新凭据由后端通过 HttpOnly Cookie 管理。
  publish({
    accessToken: data.accessToken,
    session: data.session,
    user: snapshot.user,
    status: snapshot.user ? 'authenticated' : 'recovering',
  })
}

export function beginWebSession(data: WebSessionData) {
  // 新登录可能切换主体；先移除旧用户，待 /users/me 确认新主体后才允许挂载受保护页面。
  publish({ accessToken: data.accessToken, session: data.session, user: null, status: 'recovering' })
}

export function setWebAccessToken(accessToken: string) {
  // 允许 API client 测试和后续会话恢复只更新短期凭据，不引入持久化存储。
  publish({ ...snapshot, accessToken })
}

export function setWebUser(user: User) {
  publish({ ...snapshot, user, status: 'authenticated' })
}

export function clearWebSession() {
  publish(EMPTY_SNAPSHOT)
}

export function markWebSessionRecoveryFailed() {
  // 网络和 5xx 不等同于退出；保留内存态并让用户显式决定是否再次尝试恢复。
  publish({ ...snapshot, status: 'recovery_failed' })
}

export function initializeWebSession(recover: () => Promise<void>) {
  if (snapshot.status !== 'unknown' && snapshot.status !== 'recovering') return Promise.resolve()
  if (initializationPromise) return initializationPromise

  publish({ ...snapshot, status: 'recovering' })
  // 模块级 Promise 让 StrictMode 的重复 effect 共享同一次 Refresh Token 轮换。
  initializationPromise = recover()
  return initializationPromise
}

export function retryWebSessionInitialization(recover: () => Promise<void>) {
  initializationPromise = null
  publish({ ...snapshot, status: 'unknown' })
  return initializeWebSession(recover)
}

export function resetWebSessionForTests() {
  initializationPromise = null
  publish(INITIAL_SNAPSHOT)
}

export function getWebAccessToken() {
  return snapshot.accessToken
}

export function subscribeWebAuth(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getWebAuthSnapshot() {
  return snapshot
}

export function useWebAuth() {
  return useSyncExternalStore(subscribeWebAuth, getWebAuthSnapshot, getWebAuthSnapshot)
}
