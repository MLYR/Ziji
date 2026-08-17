import type { components } from '@ziji/api-types';

import { ApiClientError, type MobileAuthApiClient } from '@/api/api-client';
import {
  createDeviceIdentityProvider,
  MobileAuthenticationSession,
  SecureCredentialWriteError,
} from '@/auth/auth-session';
import type { SecureCredentialStore } from '@/storage/secure-credentials';

type MobileSessionEnvelope = components['schemas']['MobileSessionEnvelope'];

function sessionResponse(id = 'session-1', refreshToken = 'refresh-new'): MobileSessionEnvelope {
  return {
    data: {
      session: { id, deviceId: 'device-1', deviceName: 'Ziji Mobile', createdAt: '2026-08-17T00:00:00Z', lastSeenAt: '2026-08-17T00:00:00Z', status: 'ACTIVE' },
      tokens: { accessToken: 'access-token', refreshToken, expiresIn: 1800 },
    },
    meta: { requestId: 'request-1' },
  };
}

function invalidCredentials(): ApiClientError {
  return new ApiClientError({ type: 'about:blank', title: '认证失败', status: 401, code: 'INVALID_CREDENTIALS', requestId: 'request-1' });
}

function createCredentialStore(refreshToken: string | null = null): jest.Mocked<SecureCredentialStore> {
  let storedRefreshToken = refreshToken;
  let storedDeviceId: string | null = null;
  return {
    clearRefreshCredential: jest.fn(async () => { storedRefreshToken = null; }),
    readDeviceId: jest.fn(async () => storedDeviceId),
    readRefreshCredential: jest.fn(async () => storedRefreshToken),
    writeDeviceId: jest.fn(async (value) => { storedDeviceId = value; }),
    writeRefreshCredential: jest.fn(async (value) => { storedRefreshToken = value; }),
  };
}

function createApi(overrides: Partial<jest.Mocked<MobileAuthApiClient>> = {}): jest.Mocked<MobileAuthApiClient> {
  return {
    createMobileSession: jest.fn(),
    createRegistrationChallenge: jest.fn(),
    refreshMobileSession: jest.fn(),
    registerUser: jest.fn(),
    revokeCurrentSession: jest.fn(),
    ...overrides,
  };
}

describe('MobileAuthenticationSession', () => {
  it('登录安全存储 refresh token 后才发布进程内 access token', async () => {
    const credentials = createCredentialStore();
    const api = createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await session.signIn('user@example.com', 'password');

    expect(credentials.writeRefreshCredential).toHaveBeenCalledWith('refresh-new');
    expect(session.getState().status).toBe('AUTHENTICATED');
    expect(session.getAccessToken()).toBe('access-token');
    expect(api.createMobileSession).toHaveBeenCalledWith(expect.objectContaining({ deviceId: expect.stringMatching(/^ziji-/), deviceName: 'Ziji Mobile' }));
  });

  it('凭据安全存储失败时不暴露已登录状态', async () => {
    const credentials = createCredentialStore();
    credentials.writeRefreshCredential.mockRejectedValueOnce(new Error('secure store unavailable'));
    const api = createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await expect(session.signIn('user@example.com', 'password')).rejects.toBeInstanceOf(SecureCredentialWriteError);
    expect(credentials.clearRefreshCredential).toHaveBeenCalled();
    expect(session.getState().status).toBe('UNAUTHENTICATED');
    expect(session.getAccessToken()).toBeNull();
  });

  it('启动恢复用新 refresh token 原子替换旧 token，并保持同一 sessionId', async () => {
    const credentials = createCredentialStore('refresh-old');
    const api = createApi({ refreshMobileSession: jest.fn().mockResolvedValue(sessionResponse('stable-session', 'refresh-rotated')) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await session.restore();

    expect(api.refreshMobileSession).toHaveBeenCalledWith({ refreshToken: 'refresh-old' });
    expect(credentials.writeRefreshCredential).toHaveBeenCalledWith('refresh-rotated');
    expect(session.getState()).toMatchObject({ status: 'AUTHENTICATED', session: { id: 'stable-session' } });
  });

  it('并发恢复只消费一次 refresh token', async () => {
    const credentials = createCredentialStore('refresh-old');
    let resolveRefresh: (value: MobileSessionEnvelope) => void = () => undefined;
    const pendingRefresh = new Promise<MobileSessionEnvelope>((resolve) => { resolveRefresh = resolve; });
    const api = createApi({ refreshMobileSession: jest.fn().mockReturnValue(pendingRefresh) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    const first = session.restore();
    const second = session.restore();
    resolveRefresh(sessionResponse());
    await Promise.all([first, second]);

    expect(api.refreshMobileSession).toHaveBeenCalledTimes(1);
  });

  it('401 清除本地认证材料，网络或 5xx 则保留以供恢复', async () => {
    const invalidCredentialsStore = createCredentialStore('refresh-old');
    const invalidCredentialsSession = new MobileAuthenticationSession(
      createApi({ refreshMobileSession: jest.fn().mockRejectedValue(invalidCredentials()) }),
      invalidCredentialsStore,
      createDeviceIdentityProvider(invalidCredentialsStore),
    );

    await invalidCredentialsSession.restore();
    expect(invalidCredentialsStore.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(invalidCredentialsSession.getState().status).toBe('UNAUTHENTICATED');

    const recoverableStore = createCredentialStore('refresh-old');
    const recoverableSession = new MobileAuthenticationSession(
      createApi({ refreshMobileSession: jest.fn().mockRejectedValue(new TypeError('network failed')) }),
      recoverableStore,
      createDeviceIdentityProvider(recoverableStore),
    );

    await recoverableSession.restore();
    expect(recoverableStore.clearRefreshCredential).not.toHaveBeenCalled();
    expect(recoverableSession.getState().status).toBe('RECOVERABLE_ERROR');

    for (const status of [500, 503]) {
      const serverErrorStore = createCredentialStore('refresh-old');
      const serverErrorSession = new MobileAuthenticationSession(
        createApi({ refreshMobileSession: jest.fn().mockRejectedValue(new ApiClientError({ code: 'TEMPORARILY_UNAVAILABLE', requestId: 'request-1', status, title: '服务暂不可用', type: 'about:blank' })) }),
        serverErrorStore,
        createDeviceIdentityProvider(serverErrorStore),
      );

      await serverErrorSession.restore();
      expect(serverErrorStore.clearRefreshCredential).not.toHaveBeenCalled();
      expect(serverErrorSession.getState().status).toBe('RECOVERABLE_ERROR');
      expect(serverErrorSession.getAccessToken()).toBeNull();
    }
  });

  it('安全存储读取失败时不调用刷新接口或清除凭据', async () => {
    const credentials = createCredentialStore('refresh-old');
    credentials.readRefreshCredential.mockRejectedValueOnce(new Error('secure store locked'));
    const api = createApi();
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await expect(session.restore()).resolves.toMatchObject({ status: 'RECOVERABLE_ERROR', errorMessage: expect.stringContaining('无法读取') });
    expect(api.refreshMobileSession).not.toHaveBeenCalled();
    expect(credentials.clearRefreshCredential).not.toHaveBeenCalled();
    expect(session.getAccessToken()).toBeNull();
  });

  it('刷新轮换后安全存储失败时明确退出，不把已消费旧凭据误报为可恢复', async () => {
    const credentials = createCredentialStore('refresh-old');
    credentials.writeRefreshCredential.mockRejectedValueOnce(new Error('secure store unavailable'));
    const session = new MobileAuthenticationSession(
      createApi({ refreshMobileSession: jest.fn().mockResolvedValue(sessionResponse()) }),
      credentials,
      createDeviceIdentityProvider(credentials),
    );

    await session.restore();

    expect(credentials.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(session.getState().status).toBe('UNAUTHENTICATED');
    expect(session.getAccessToken()).toBeNull();
  });

  it('当前设备退出即使远端不可达也清除本地凭据', async () => {
    const credentials = createCredentialStore();
    const api = createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()), revokeCurrentSession: jest.fn().mockRejectedValue(new TypeError('offline')) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));
    await session.signIn('user@example.com', 'password');

    const result = await session.signOut();

    expect(result).toEqual({ localCredentialsCleared: true, remoteSessionRevoked: false });
    expect(credentials.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(session.getState().status).toBe('UNAUTHENTICATED');
    expect(session.getAccessToken()).toBeNull();
  });

  it('冷启动无 access token 时仍可执行本机安全退出', async () => {
    const credentials = createCredentialStore('refresh-old');
    const api = createApi();
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await expect(session.signOut()).resolves.toEqual({ localCredentialsCleared: true, remoteSessionRevoked: false });
    expect(api.revokeCurrentSession).not.toHaveBeenCalled();
    expect(credentials.clearRefreshCredential).toHaveBeenCalledTimes(1);
  });

  it('安全存储删除失败时不伪装为已退出，保留可重试的本机安全退出状态', async () => {
    const credentials = createCredentialStore('refresh-old');
    credentials.clearRefreshCredential.mockRejectedValueOnce(new Error('secure store locked'));
    const session = new MobileAuthenticationSession(createApi(), credentials, createDeviceIdentityProvider(credentials));

    await expect(session.signOut()).resolves.toEqual({ localCredentialsCleared: false, remoteSessionRevoked: false });
    expect(session.getState()).toMatchObject({ status: 'RECOVERABLE_ERROR', errorMessage: expect.stringContaining('无法清除') });

    const invalidSession = new MobileAuthenticationSession(
      createApi({ refreshMobileSession: jest.fn().mockRejectedValue(invalidCredentials()) }),
      credentials,
      createDeviceIdentityProvider(credentials),
    );
    credentials.clearRefreshCredential.mockRejectedValueOnce(new Error('secure store locked'));
    await invalidSession.restore();
    expect(invalidSession.getState().status).toBe('RECOVERABLE_ERROR');
  });
});
