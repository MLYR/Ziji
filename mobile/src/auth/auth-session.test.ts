import type { components } from '@ziji/api-types';

import { ApiClientError, type MobileAuthApiClient } from '@/api/api-client';
import {
  createDeviceIdentityProvider,
  MobileAuthenticationSession,
  SecureCredentialWriteError,
} from '@/auth/auth-session';
import type { SecureCredentialStore } from '@/storage/secure-credentials';

type MobileSessionEnvelope = components['schemas']['MobileSessionEnvelope'];

function currentUserResponse(id = 'user-1'): components['schemas']['UserEnvelope'] {
  return {
    data: { id, email: 'user@example.com', nickname: '资迹', timezone: 'Asia/Shanghai', baseCurrency: 'CNY', locale: 'zh-CN', amountFormat: 'STANDARD', status: 'ACTIVE', version: 1 },
    meta: { requestId: 'request-1' },
  };
}

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
    getCurrentUser: jest.fn().mockResolvedValue(currentUserResponse()),
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
    expect(session.getState().userId).toBe('user-1');
    expect(api.getCurrentUser).toHaveBeenCalledTimes(1);
    expect(api.createMobileSession).toHaveBeenCalledWith(expect.objectContaining({ deviceId: expect.stringMatching(/^ziji-/), deviceName: 'Ziji Mobile' }));
  });

  it('主体确认完成前不发布 AUTHENTICATED 或 access token', async () => {
    let resolveUser: (value: components['schemas']['UserEnvelope']) => void = () => undefined;
    const userPending = new Promise<components['schemas']['UserEnvelope']>((resolve) => { resolveUser = resolve; });
    const credentials = createCredentialStore();
    const session = new MobileAuthenticationSession(
      createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()), getCurrentUser: jest.fn().mockReturnValue(userPending) }),
      credentials,
      createDeviceIdentityProvider(credentials),
    );

    const signIn = session.signIn('user@example.com', 'password');
    await Promise.resolve();
    expect(session.getState().status).not.toBe('AUTHENTICATED');

    resolveUser(currentUserResponse());
    await signIn;
    expect(session.getState()).toMatchObject({ status: 'AUTHENTICATED', userId: 'user-1' });
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

    const forbiddenStore = createCredentialStore('refresh-old');
    const forbiddenSession = new MobileAuthenticationSession(
      createApi({ refreshMobileSession: jest.fn().mockRejectedValue(new ApiClientError({ type: 'about:blank', title: '无权刷新', status: 403, code: 'FORBIDDEN', requestId: 'request-1' })) }),
      forbiddenStore,
      createDeviceIdentityProvider(forbiddenStore),
    );
    await forbiddenSession.restore();
    expect(forbiddenStore.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(forbiddenSession.getState().status).toBe('UNAUTHENTICATED');

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

  it.each([401, 403])('主体确认返回 %i 时清除认证材料且不开放用户 scope', async (status) => {
    const credentials = createCredentialStore();
    const session = new MobileAuthenticationSession(
      createApi({
        createMobileSession: jest.fn().mockResolvedValue(sessionResponse()),
        getCurrentUser: jest.fn().mockRejectedValue(new ApiClientError({ type: 'about:blank', title: '认证失败', status, code: status === 401 ? 'INVALID_CREDENTIALS' : 'FORBIDDEN', requestId: 'request-1' })),
      }),
      credentials,
      createDeviceIdentityProvider(credentials),
    );

    await session.signIn('user@example.com', 'password');

    expect(credentials.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(session.getState()).toMatchObject({ status: 'UNAUTHENTICATED', userId: null });
    expect(session.getAccessToken()).toBeNull();
  });

  it('主体确认网络或 5xx 失败时保留刷新凭据但不开放用户 scope', async () => {
    for (const error of [new TypeError('offline'), new ApiClientError({ type: 'about:blank', title: '服务失败', status: 503, code: 'INTERNAL_ERROR', requestId: 'request-1' })]) {
      const credentials = createCredentialStore();
      const session = new MobileAuthenticationSession(
        createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()), getCurrentUser: jest.fn().mockRejectedValue(error) }),
        credentials,
        createDeviceIdentityProvider(credentials),
      );

      await session.signIn('user@example.com', 'password');

      expect(credentials.clearRefreshCredential).not.toHaveBeenCalled();
      expect(session.getState()).toMatchObject({ status: 'RECOVERABLE_ERROR', userId: null });
      expect(session.getAccessToken()).toBeNull();
    }
  });

  it('刷新主体变化时清除本轮凭据并保留用户隔离边界', async () => {
    const credentials = createCredentialStore();
    const api = createApi({
      createMobileSession: jest.fn().mockResolvedValue(sessionResponse()),
      refreshMobileSession: jest.fn().mockResolvedValue(sessionResponse('session-1', 'refresh-rotated')),
      getCurrentUser: jest.fn().mockResolvedValueOnce(currentUserResponse('user-a')).mockResolvedValueOnce(currentUserResponse('user-b')),
    });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));

    await session.signIn('user@example.com', 'password');
    await session.restore();

    expect(credentials.clearRefreshCredential).toHaveBeenCalledTimes(1);
    expect(session.getState()).toMatchObject({ status: 'UNAUTHENTICATED', userId: null });
    expect(session.getAccessToken()).toBeNull();
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

  it('认证失效路径先关闭主体 scope，再清理 SecureStore 且不调用远端退出', async () => {
    const credentials = createCredentialStore();
    const api = createApi({ createMobileSession: jest.fn().mockResolvedValue(sessionResponse()) });
    const session = new MobileAuthenticationSession(api, credentials, createDeviceIdentityProvider(credentials));
    await session.signIn('user@example.com', 'password');

    let finishClear: () => void = () => undefined;
    credentials.clearRefreshCredential.mockImplementationOnce(() => new Promise<void>((resolve) => { finishClear = resolve; }));
    const invalidation = session.invalidateAuthentication();
    expect(session.getState()).toMatchObject({ status: 'UNAUTHENTICATED', userId: null });
    expect(session.getAccessToken()).toBeNull();
    expect(api.revokeCurrentSession).not.toHaveBeenCalled();
    finishClear();
    await expect(invalidation).resolves.toBe(true);
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
