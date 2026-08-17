import { createMobileAuthApiClient } from '@/api/api-client';
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
