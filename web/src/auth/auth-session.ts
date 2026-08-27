import type { components } from '@ziji/api-types'
import { useSyncExternalStore } from 'react'

type Session = components['schemas']['Session']
type User = components['schemas']['User']
type WebSessionData = components['schemas']['WebSessionEnvelope']['data']

export interface WebAuthSnapshot {
  accessToken: string | null
  session: Session | null
  user: User | null
}

const EMPTY_SNAPSHOT: WebAuthSnapshot = { accessToken: null, session: null, user: null }

let snapshot = EMPTY_SNAPSHOT
const listeners = new Set<() => void>()

function publish(next: WebAuthSnapshot) {
  snapshot = next
  listeners.forEach((listener) => listener())
}

export function setWebSession(data: WebSessionData) {
  // Web Access Token 仅驻留内存；刷新凭据由后端通过 HttpOnly Cookie 管理。
  publish({ accessToken: data.accessToken, session: data.session, user: null })
}

export function setWebAccessToken(accessToken: string) {
  // 允许 API client 测试和后续会话恢复只更新短期凭据，不引入持久化存储。
  publish({ ...snapshot, accessToken })
}

export function setWebUser(user: User) {
  publish({ ...snapshot, user })
}

export function clearWebSession() {
  publish(EMPTY_SNAPSHOT)
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
