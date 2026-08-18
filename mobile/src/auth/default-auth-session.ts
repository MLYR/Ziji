import { createMobileAuthApiClient, createMobileSyncApiClient, createMobileTransactionApiClient } from '@/api/api-client';
import { createDeviceIdentityProvider, MobileAuthenticationSession } from '@/auth/auth-session';
import { secureCredentialStore } from '@/storage/secure-credentials';

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

let authenticationSession: MobileAuthenticationSession | undefined;

const api = createMobileAuthApiClient({
  baseUrl: apiBaseUrl,
  // 闭包仅读取进程内 accessToken；认证状态本身不携带或持久化 Token。
  readAccessToken: async () => authenticationSession?.getAccessToken() ?? null,
});

authenticationSession = new MobileAuthenticationSession(api, secureCredentialStore, createDeviceIdentityProvider(secureCredentialStore));

export const mobileAuthenticationSession = authenticationSession;

// 同步与冲突详情复用已确认主体的进程内 Bearer，不读取或复制安全存储中的刷新凭据。
const readAccessToken = async () => authenticationSession?.getAccessToken() ?? null;

export const mobileSyncApiClient = createMobileSyncApiClient({ baseUrl: apiBaseUrl, readAccessToken });
export const mobileTransactionApiClient = createMobileTransactionApiClient({ baseUrl: apiBaseUrl, readAccessToken });
export const mobileDeviceIdentity = createDeviceIdentityProvider(secureCredentialStore);
