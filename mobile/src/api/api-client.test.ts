import { createMobileApiClient, createMobileSyncApiClient } from './api-client';

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
});
