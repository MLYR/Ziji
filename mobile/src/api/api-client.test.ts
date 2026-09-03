import { createMobileAccountsApiClient, createMobileApiClient, createMobileAuthApiClient, createMobileSyncApiClient, createMobileTransactionApiClient } from './api-client';

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


  it('封装负债详情读取与写入（If-None-Match/If-Match 前置条件）', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ data: { accountId: 'a-1', interestRate: null, loanDate: null, dueDate: null, billingDay: null, repaymentDay: null, currentAmountDue: null, version: 0 }, meta: { requestId: 'request-1' } }), { status: 200 }),
    );
    const client = createMobileAccountsApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.getLiabilityDetails('a-1');
    expect(fetchMock.mock.calls[0]?.[0].toString()).toBe('https://api.ziji.test/api/v1/accounts/a-1/liability-details');

    fetchMock.mockImplementation(async () =>
      new Response(JSON.stringify({ data: { accountId: 'a-1', interestRate: '0.045', loanDate: null, dueDate: null, billingDay: 10, repaymentDay: 10, currentAmountDue: '32.00', version: 1 } }), { status: 200 }),
    );
    await client.putLiabilityDetails('a-1', { ifMatch: '"1"' }, 'key-1', {
      interestRate: '0.045', loanDate: null, dueDate: null, billingDay: 10, repaymentDay: 10, currentAmountDue: '32.00',
    });
    const putInit = fetchMock.mock.calls[1]?.[1];
    expect(putInit?.method).toBe('PUT');
    expect(new Headers(putInit?.headers).get('If-Match')).toBe('"1"');
    expect(new Headers(putInit?.headers).get('Idempotency-Key')).toBe('key-1');
    expect(new Headers(putInit?.headers).get('If-None-Match')).toBeNull();

    await client.putLiabilityDetails('a-1', { ifNoneMatch: true }, 'key-2', {
      interestRate: null, loanDate: null, dueDate: null, billingDay: null, repaymentDay: null, currentAmountDue: null,
    });
    expect(new Headers(fetchMock.mock.calls[2]?.[1]?.headers).get('If-None-Match')).toBe('*');
    expect(new Headers(fetchMock.mock.calls[2]?.[1]?.headers).get('If-Match')).toBeNull();
  });

  it('使用生成类型封装 Mobile 注册、登录、刷新和当前设备退出', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { expiresIn: 600 }, meta: { requestId: 'request-1' } }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { id: 'user-1' }, meta: { requestId: 'request-2' } }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { session: {}, tokens: {} }, meta: { requestId: 'request-3' } }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { session: {}, tokens: {} }, meta: { requestId: 'request-4' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { id: 'user-1' }, meta: { requestId: 'request-5' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createMobileAuthApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.createRegistrationChallenge({ email: 'user@example.com', deviceId: 'device-a' });
    await client.registerUser({ email: 'user@example.com', password: 'password', verificationCode: '123456', nickname: '资迹', timezone: 'Asia/Shanghai', baseCurrency: 'CNY', locale: 'zh-CN' }, 'register-key');
    await client.createMobileSession({ email: 'user@example.com', password: 'password', deviceId: 'device-a', deviceName: 'Ziji Mobile' });
    await client.refreshMobileSession({ refreshToken: 'refresh-token' });
    await client.getCurrentUser();
    await client.revokeCurrentSession();

    expect(fetchMock.mock.calls.map(([url]) => url.toString())).toEqual([
      'https://api.ziji.test/api/v1/auth/registration-challenges',
      'https://api.ziji.test/api/v1/auth/register',
      'https://api.ziji.test/api/v1/auth/mobile/sessions',
      'https://api.ziji.test/api/v1/auth/mobile/sessions/refresh',
      'https://api.ziji.test/api/v1/users/me',
      'https://api.ziji.test/api/v1/auth/sessions/current',
    ]);
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('register-key');
    expect(fetchMock.mock.calls.every(([, init]) => init?.credentials === 'omit')).toBe(true);
    expect(fetchMock.mock.calls.slice(0, 4).every(([, init]) => new Headers(init?.headers).get('Authorization') === null)).toBe(true);
    expect(new Headers(fetchMock.mock.calls[4]?.[1]?.headers).get('Authorization')).toBe('Bearer access-test');
    expect(new Headers(fetchMock.mock.calls[5]?.[1]?.headers).get('Authorization')).toBe('Bearer access-test');
  });

  it('通过类型化交易客户端请求固定 transactionId 路径', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ data: { id: 'transaction-1' }, meta: { requestId: 'request-1' } }), { status: 200 }),
    );
    const client = createMobileTransactionApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.getTransaction('transaction / id');

    expect(fetchMock.mock.calls[0]?.[0].toString()).toBe('https://api.ziji.test/api/v1/transactions/transaction%20%2F%20id');
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer access-test');
    expect(fetchMock.mock.calls[0]?.[1]?.credentials).toBe('omit');
  });

  it('更新账户经真实请求层发送 merge-patch Media Type', async () => {
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ data: { id: 'account-1' }, meta: { requestId: 'request-1' } }), { status: 200 }),
    );
    const client = createMobileAccountsApiClient({ baseUrl: 'https://api.ziji.test/', readAccessToken: async () => 'access-test' });

    await client.updateAccount('account / id', '"2"', { name: '新名称' });

    expect(fetchMock.mock.calls[0]?.[0].toString()).toBe('https://api.ziji.test/api/v1/accounts/account%20%2F%20id');
    const init = fetchMock.mock.calls[0]?.[1];
    expect(init?.method).toBe('PATCH');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-test');
    expect(new Headers(init?.headers).get('If-Match')).toBe('"2"');
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/merge-patch+json');
    expect(init?.body).toBe(JSON.stringify({ name: '新名称' }));
  });
});
