import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import { ApiClientError } from '@/api/api-client';

const mockDatabase = {};
const mockListPendingOperations = jest.fn();
const mockGetSyncConflict = jest.fn();
const mockSynchronize = jest.fn();
const mockDiscardLocal = jest.fn();
const mockRetryWithRevision = jest.fn();
const mockGetTransaction = jest.fn();
const mockRefresh = jest.fn();
const mockInvalidateAuthentication = jest.fn();
const mockLease = {
  userId: 'user-a',
  generation: 1,
  accessToken: 'access-token',
  isCurrent: () => true,
  assertCurrent: () => undefined,
  withOperation: async <T,>(operation: () => Promise<T>) => operation(),
};

jest.mock('@/auth/default-auth-session', () => ({
  createMobileSyncApiClientForLease: () => ({}),
  createMobileTransactionApiClientForLease: () => ({ getTransaction: (transactionId: string) => mockGetTransaction(transactionId) }),
  mobileAuthenticationSession: {
    invalidateAuthentication: () => mockInvalidateAuthentication(),
    refresh: () => mockRefresh(),
    getState: () => ({ errorMessage: null, session: {}, status: 'AUTHENTICATED', userId: 'user-a' }),
    getCurrentScopeLease: () => mockLease,
  },
  mobileDeviceIdentity: { get: jest.fn().mockResolvedValue({ deviceId: 'device-a', deviceName: 'Ziji Mobile' }) },
  mobileSyncApiClient: {},
  mobileTransactionApiClient: { getTransaction: (transactionId: string) => mockGetTransaction(transactionId) },
}));

jest.mock('@/storage/local-database', () => ({
  getLocalDatabase: jest.fn().mockResolvedValue(mockDatabase),
  getSyncConflict: (...args: unknown[]) => mockGetSyncConflict(...args),
  listPendingOperations: (...args: unknown[]) => mockListPendingOperations(...args),
}));

jest.mock('@/sync/sync-coordinator', () => ({ synchronize: (...args: unknown[]) => mockSynchronize(...args) }));
jest.mock('@/sync/conflict-resolution', () => ({
  createRevisionOperation: jest.fn(() => ({ operationId: 'new-operation', idempotencyKey: 'new-key' })),
  createSyncConflictResolutionPort: () => ({
    discardLocal: (...args: unknown[]) => mockDiscardLocal(...args),
    retryWithRevision: (...args: unknown[]) => mockRetryWithRevision(...args),
  }),
}));

const { SyncStatusPanel, mapSyncStatus } = require('./sync-status-panel') as typeof import('./sync-status-panel');

const conflictOperation = {
  baseVersion: 1,
  createdAt: '2026-08-18T00:00:00Z',
  entityId: '123e4567-e89b-42d3-a456-426614174000',
  entityType: 'TRANSACTION' as const,
  idempotencyKey: 'old-key',
  operationId: 'old-operation',
  operationType: 'UPDATE' as const,
  payload: { reason: '旧原因', replacement: { businessDate: '2026-08-18', type: 'EXPENSE' } },
  payloadVersion: 1 as const,
  retryAfterAt: null,
  state: 'CONFLICT' as const,
  updatedAt: '2026-08-18T00:00:00Z',
};

describe('SyncStatusPanel', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockListPendingOperations.mockResolvedValue([conflictOperation]);
    mockGetSyncConflict.mockResolvedValue({
      createdAt: '2026-08-18T00:00:00Z',
      operationId: 'old-operation',
      problem: {
        code: 'VERSION_CONFLICT', requestId: 'request-1', status: 409, title: '版本冲突', type: 'about:blank',
        versionConflict: { currentEtag: '"2"', currentVersion: 2, resourceLocation: '/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000' },
      },
    });
    mockDiscardLocal.mockResolvedValue(undefined);
    mockRetryWithRevision.mockResolvedValue(undefined);
    mockSynchronize.mockResolvedValue(undefined);
    mockRefresh.mockResolvedValue({ errorMessage: null, session: {}, status: 'AUTHENTICATED', userId: 'user-a' });
    mockInvalidateAuthentication.mockResolvedValue(true);
  });

  it('从 SQLite 状态呈现冲突操作、可访问动作和深浅主题样式', async () => {
    const view = await render(<SyncStatusPanel userId="user-a" />);

    await view.findByText('UPDATE：需要处理冲突');
    expect(view.getByLabelText('查看云端交易').props.accessibilityRole).toBe('button');
    expect(view.getByLabelText('接受云端并放弃本地').props.accessibilityRole).toBe('button');
    expect(view.getByLabelText('修订后重试').props.className).toContain('bg-accent');
    expect(view.getByText('同步状态').parent?.parent?.props.className).toContain('dark:bg-surface-dark');
    expect(view.getByText('本地修改')).toBeTruthy();
    expect(view.getByText('语义：EXPENSE')).toBeTruthy();
    expect(view.getByText('接受云端并放弃本地只删除本机 pending 与 conflict，不写服务端。')).toBeTruthy();
    expect(view.getByText('按本地修改重试会沿用本地语义载荷，以云端当前版本和新三元组重新入队。')).toBeTruthy();
  });

  it('冲突动作复用 SQLite port，并只经安全 transactionId 请求云端详情', async () => {
    mockGetTransaction.mockResolvedValue({ data: { businessDate: '2026-08-17', id: '123e4567-e89b-42d3-a456-426614174000', status: 'POSTED', type: 'EXPENSE', version: 2 }, meta: { requestId: 'request-1' } });
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('查看云端交易'));
    await view.findByText('类型：EXPENSE');
    expect(view.getByText('业务日期：2026-08-17')).toBeTruthy();
    expect(view.getByText('版本：2')).toBeTruthy();
    expect(mockGetTransaction).toHaveBeenCalledWith('123e4567-e89b-42d3-a456-426614174000');

    await fireEvent.press(view.getByLabelText('接受云端并放弃本地'));
    await waitFor(() => expect(mockDiscardLocal).toHaveBeenCalledWith('user-a', 'old-operation'));

    await fireEvent.changeText(view.getByLabelText('修订或作废原因'), '用户已修订');
    await fireEvent.press(view.getByLabelText('修订后重试'));
    await waitFor(() => expect(mockRetryWithRevision).toHaveBeenCalledWith('user-a', 'old-operation', expect.objectContaining({ operationId: 'new-operation' })));
  });

  it('同步 401 仅在同主体刷新成功后重试一次，403 则关闭认证 scope', async () => {
    mockSynchronize
      .mockRejectedValueOnce(new ApiClientError({ code: 'UNAUTHORIZED', requestId: 'request-1', status: 401, title: '未认证', type: 'about:blank' }))
      .mockResolvedValueOnce(undefined);
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('立即同步'));

    await waitFor(() => expect(mockSynchronize).toHaveBeenCalledTimes(2));
    expect(mockRefresh).toHaveBeenCalledTimes(1);
    expect(mockInvalidateAuthentication).not.toHaveBeenCalled();

    mockSynchronize.mockRejectedValueOnce(new ApiClientError({ code: 'FORBIDDEN', requestId: 'request-2', status: 403, title: '无权', type: 'about:blank' }));
    await fireEvent.press(view.getByLabelText('立即同步'));
    await waitFor(() => expect(mockInvalidateAuthentication).toHaveBeenCalledTimes(1));
  });

  it('同步 401 刷新到不同主体时不重试或改写本地队列', async () => {
    mockSynchronize.mockRejectedValueOnce(new ApiClientError({ code: 'UNAUTHORIZED', requestId: 'request-1', status: 401, title: '未认证', type: 'about:blank' }));
    mockRefresh.mockResolvedValueOnce({ errorMessage: null, session: null, status: 'AUTHENTICATED', userId: 'user-b' });
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('立即同步'));

    await waitFor(() => expect(mockRefresh).toHaveBeenCalledTimes(1));
    expect(mockSynchronize).toHaveBeenCalledTimes(1);
    expect(mockDiscardLocal).not.toHaveBeenCalled();
    expect(mockRetryWithRevision).not.toHaveBeenCalled();
  });

  it('云端详情 401 只在同主体刷新成功后以安全 transactionId 重试，403 关闭认证 scope', async () => {
    mockGetTransaction
      .mockRejectedValueOnce(new ApiClientError({ code: 'UNAUTHORIZED', requestId: 'request-1', status: 401, title: '未认证', type: 'about:blank' }))
      .mockResolvedValueOnce({ data: { businessDate: '2026-08-17', id: '123e4567-e89b-42d3-a456-426614174000', status: 'POSTED', type: 'EXPENSE', version: 2 }, meta: { requestId: 'request-1' } });
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('查看云端交易'));
    await view.findByText('类型：EXPENSE');
    expect(mockGetTransaction).toHaveBeenNthCalledWith(1, '123e4567-e89b-42d3-a456-426614174000');
    expect(mockGetTransaction).toHaveBeenNthCalledWith(2, '123e4567-e89b-42d3-a456-426614174000');
    expect(mockInvalidateAuthentication).not.toHaveBeenCalled();

    mockGetTransaction.mockRejectedValueOnce(new ApiClientError({ code: 'FORBIDDEN', requestId: 'request-2', status: 403, title: '无权', type: 'about:blank' }));
    await fireEvent.press(view.getByLabelText('查看云端交易'));
    await waitFor(() => expect(mockInvalidateAuthentication).toHaveBeenCalledTimes(1));
  });

  it('云端详情 401 刷新到不同主体时不重试且不改写冲突', async () => {
    mockGetTransaction.mockRejectedValueOnce(new ApiClientError({ code: 'UNAUTHORIZED', requestId: 'request-1', status: 401, title: '未认证', type: 'about:blank' }));
    mockRefresh.mockResolvedValueOnce({ errorMessage: null, session: null, status: 'AUTHENTICATED', userId: 'user-b' });
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('查看云端交易'));

    await waitFor(() => expect(mockRefresh).toHaveBeenCalledTimes(1));
    expect(mockGetTransaction).toHaveBeenCalledTimes(1);
    expect(mockDiscardLocal).not.toHaveBeenCalled();
    expect(mockRetryWithRevision).not.toHaveBeenCalled();
  });

  it('拒绝不安全 conflict location，绝不把它当作请求地址', async () => {
    mockGetSyncConflict.mockResolvedValueOnce({
      createdAt: '2026-08-18T00:00:00Z', operationId: 'old-operation',
      problem: {
        code: 'VERSION_CONFLICT', requestId: 'request-1', status: 409, title: '版本冲突', type: 'about:blank',
        versionConflict: { currentEtag: '"2"', currentVersion: 2, resourceLocation: 'https://evil.example/transactions/123e4567-e89b-42d3-a456-426614174000' },
      },
    });
    const view = await render(<SyncStatusPanel userId="user-a" />);
    await view.findByText('UPDATE：需要处理冲突');

    await fireEvent.press(view.getByLabelText('查看云端交易'));

    await view.findByText('服务端冲突定位无效，已拒绝请求。');
    expect(mockGetTransaction).not.toHaveBeenCalled();
  });

  it('拒绝状态不自动重试，重启读取时直接恢复 SQLite 队列', async () => {
    mockListPendingOperations.mockResolvedValue([{ ...conflictOperation, operationId: 'rejected-operation', state: 'REJECTED' as const }]);
    const view = await render(<SyncStatusPanel userId="user-a" />);

    await view.findByText('UPDATE：服务端拒绝');
    expect(view.getByText('服务端已拒绝，不会自动重试；请检查输入后重新创建操作。')).toBeTruthy();
    expect(mockSynchronize).not.toHaveBeenCalled();
  });

  it('映射 PENDING、SENDING、RETRYABLE 与 REJECTED 可读状态', () => {
    expect(mapSyncStatus({ ...conflictOperation, state: 'PENDING', retryAfterAt: null } as never)).toBe('待同步');
    expect(mapSyncStatus({ ...conflictOperation, state: 'PENDING', retryAfterAt: '2026-08-18T00:00:05Z' } as never)).toBe('等待重试');
    expect(mapSyncStatus({ ...conflictOperation, state: 'SENDING' } as never)).toBe('同步中');
    expect(mapSyncStatus({ ...conflictOperation, state: 'REJECTED' } as never)).toBe('服务端拒绝');
  });
});
