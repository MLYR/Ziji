import { createMobileAuthApiClient, createMobileSyncApiClient, createMobileTransactionApiClient } from '@/api/api-client';
import { createDeviceIdentityProvider, MobileAuthenticationSession, type MobileAuthenticationScopeLease } from '@/auth/auth-session';
import { closeLocalDatabase } from '@/storage/local-database';
import { secureCredentialStore } from '@/storage/secure-credentials';

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

let authenticationSession: MobileAuthenticationSession | undefined;

const api = createMobileAuthApiClient({
  baseUrl: apiBaseUrl,
  // 闭包仅读取进程内 accessToken；认证状态本身不携带或持久化 Token。
  readAccessToken: async () => authenticationSession?.getAccessToken() ?? null,
});

authenticationSession = new MobileAuthenticationSession(
  api,
  secureCredentialStore,
  createDeviceIdentityProvider(secureCredentialStore),
  closeLocalDatabase,
);

export const mobileAuthenticationSession = authenticationSession;
export const mobileAuthApiClient = api;

// 同步与冲突详情复用已确认主体的进程内 Bearer，不读取或复制安全存储中的刷新凭据。
const readAccessToken = async () => authenticationSession?.getAccessToken() ?? null;

export const mobileSyncApiClient = createMobileSyncApiClient({ baseUrl: apiBaseUrl, readAccessToken });
export const mobileTransactionApiClient = createMobileTransactionApiClient({ baseUrl: apiBaseUrl, readAccessToken });
export const mobileDeviceIdentity = createDeviceIdentityProvider(secureCredentialStore);

export function createMobileSyncApiClientForLease(lease: MobileAuthenticationScopeLease) {
  // 同步请求绑定发起时的 access token；旧任务失效后不得读取新主体的全局 Token。
  return createMobileSyncApiClient({
    baseUrl: apiBaseUrl,
    readAccessToken: async () => (lease.isCurrent() ? lease.accessToken : null),
  });
}

export function createMobileTransactionApiClientForLease(lease: MobileAuthenticationScopeLease) {
  // 冲突详情和同步使用同一主体 lease，避免旧闭包跨用户请求云端资源。
  return createMobileTransactionApiClient({
    baseUrl: apiBaseUrl,
    readAccessToken: async () => (lease.isCurrent() ? lease.accessToken : null),
  });
}
