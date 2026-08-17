import { createMobileApiClient, createMobileAuthApiClient, createMobileSyncApiClient } from './api-client';

describe('Mobile API client', () => {
  afterEach(() => {
    // 恢复原生 fetch，确保其他移动端测试不依赖本用例替身。
    jest.restoreAllMocks();
  });

  it('使用 Bearer token 且不启用 Cookie 凭据', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const request = createMobileApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await request('/v1/example');

    const init = fetchMock.mock.calls[0]?.[1];
    expect(init?.credentials).toBe('omit');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-test');
  });

  it('使用生成类型封装同步拉取与上传路径', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ data: [], meta: { requestId: 'request-1', hasMore: false, nextCursor: null } }), { status: 200 }),
    );
    const client = createMobileSyncApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => null });

    await client.listSyncChanges('cursor value');
    await client.applySyncOperations({ deviceId: 'device-a', operations: [] });

    expect(fetchMock.mock.calls[0]?.[0].toString()).toBe('https://api.ziji.test/api/v1/sync/changes?cursor=cursor%20value');
    expect(fetchMock.mock.calls[1]?.[0].toString()).toBe('https://api.ziji.test/api/v1/sync/operations');
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(JSON.stringify({ deviceId: 'device-a', operations: [] }));
  });

  it('使用生成类型封装 Mobile 注册、登录、刷新和当前设备退出', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { expiresIn: 600 }, meta: { requestId: 'request-1' } }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { id: 'user-1' }, meta: { requestId: 'request-2' } }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { session: {}, tokens: {} }, meta: { requestId: 'request-3' } }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { session: {}, tokens: {} }, meta: { requestId: 'request-4' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createMobileAuthApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.createRegistrationChallenge({ email: 'user@example.com', deviceId: 'device-a' });
    await client.registerUser({ email: 'user@example.com', password: 'password', verificationCode: '123456', nickname: '资迹', timezone: 'Asia/Shanghai', baseCurrency: 'CNY', locale: 'zh-CN' }, 'register-key');
    await client.createMobileSession({ email: 'user@example.com', password: 'password', deviceId: 'device-a', deviceName: 'Ziji Mobile' });
    await client.refreshMobileSession({ refreshToken: 'refresh-token' });
    await client.revokeCurrentSession();

    expect(fetchMock.mock.calls.map(([url]) => url.toString())).toEqual([
      'https://api.ziji.test/api/v1/auth/registration-challenges',
      'https://api.ziji.test/api/v1/auth/register',
      'https://api.ziji.test/api/v1/auth/mobile/sessions',
      'https://api.ziji.test/api/v1/auth/mobile/sessions/refresh',
      'https://api.ziji.test/api/v1/auth/sessions/current',
    ]);
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('register-key');
    expect(fetchMock.mock.calls.every(([, init]) => init?.credentials === 'omit')).toBe(true);
    expect(fetchMock.mock.calls.slice(0, 4).every(([, init]) => new Headers(init?.headers).get('Authorization') === null)).toBe(true);
    expect(new Headers(fetchMock.mock.calls[4]?.[1]?.headers).get('Authorization')).toBe('Bearer access-test');
  });
});
