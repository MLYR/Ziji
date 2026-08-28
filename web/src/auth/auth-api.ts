import type { components, operations } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type EmailChallengeRequest = components['schemas']['EmailChallengeRequest']
export type RegisterRequest = components['schemas']['RegisterRequest']
export type LoginRequest = components['schemas']['LoginRequest']
export type PasswordResetRequest = components['schemas']['PasswordResetRequest']
export type ChallengeAccepted = operations['createRegistrationChallenge']['responses'][202]['content']['application/json']
export type UserEnvelope = components['schemas']['UserEnvelope']
export type WebSessionEnvelope = components['schemas']['WebSessionEnvelope']
export type SessionListEnvelope = components['schemas']['SessionListEnvelope']

export function createRegistrationChallenge(body: EmailChallengeRequest) {
  return apiRequest<ChallengeAccepted>('/api/v1/auth/registration-challenges', {
    method: 'POST',
    auth: false,
    body,
  })
}

export function registerUser(body: RegisterRequest, idempotencyKey: string) {
  return apiRequest<UserEnvelope>('/api/v1/auth/register', {
    method: 'POST',
    auth: false,
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
}

export function createWebSession(body: LoginRequest) {
  return apiRequest<WebSessionEnvelope>('/api/v1/auth/web/sessions', {
    method: 'POST',
    auth: false,
    body,
  })
}

export function createPasswordResetChallenge(body: EmailChallengeRequest) {
  return apiRequest<ChallengeAccepted>('/api/v1/auth/password-reset-challenges', {
    method: 'POST',
    auth: false,
    body,
  })
}

export function resetPassword(body: PasswordResetRequest, idempotencyKey: string) {
  return apiRequest<void>('/api/v1/auth/password-reset', {
    method: 'POST',
    auth: false,
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  })
}

export function getCurrentUser(retryAuthentication = true) {
  return apiRequest<UserEnvelope>('/api/v1/users/me', { retryAuthentication })
}

export function refreshWebSession() {
  return apiRequest<WebSessionEnvelope>('/api/v1/auth/web/sessions/refresh', {
    method: 'POST',
    auth: false,
    retryAuthentication: false,
  })
}

export function revokeCurrentSession() {
  return apiRequest<void>('/api/v1/auth/sessions/current', {
    method: 'DELETE',
    retryAuthentication: false,
  })
}

export function listUserSessions(cursor?: string) {
  const params = new URLSearchParams({ limit: '20' })
  if (cursor) params.set('cursor', cursor)
  return apiRequest<SessionListEnvelope>(`/api/v1/users/me/sessions?${params.toString()}`)
}

export function revokeUserSession(sessionId: string) {
  return apiRequest<void>(`/api/v1/users/me/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
}

export function revokeAllUserSessions() {
  return apiRequest<void>('/api/v1/users/me/sessions', { method: 'DELETE' })
}

export function createIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.() ?? `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
