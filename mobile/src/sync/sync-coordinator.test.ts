import type * as SQLite from 'expo-sqlite';

import type { components } from '@ziji/api-types';

import { ApiClientError, type MobileSyncApiClient } from '../api/api-client';
import {
  enqueuePendingOperation,
  getCachedEntity,
  getPendingOperation,
  getSyncConflict,
  getSyncCursor,
  listPendingOperations,
  markPendingSending,
  migrateLocalDatabase,
  type PendingSyncOperation,
} from '../storage/local-database';
import { pullChanges, pushOperations, synchronize, SyncProtocolError } from './sync-coordinator';

interface NativeStatement {
  all(...parameters: unknown[]): unknown[];
  get(...parameters: unknown[]): unknown;
  run(...parameters: unknown[]): { changes: number };
}

interface NativeDatabase {
  close(): void;
  exec(sql: string): void;
  prepare(sql: string): NativeStatement;
}

const { DatabaseSync } = require('node:sqlite') as { DatabaseSync: new (path: string) => NativeDatabase };

class TestDatabase {
  private readonly native = new DatabaseSync(':memory:');

  constructor(private readonly failSql?: string) {}

  async execAsync(sql: string): Promise<void> {
    this.native.exec(sql);
  }

  async getAllAsync<T>(sql: string, ...parameters: unknown[]): Promise<T[]> {
    return this.native.prepare(sql).all(...flatten(parameters)) as T[];
  }

  async getFirstAsync<T>(sql: string, ...parameters: unknown[]): Promise<T | null> {
    return (this.native.prepare(sql).get(...flatten(parameters)) as T | undefined) ?? null;
  }

  async runAsync(sql: string, ...parameters: unknown[]): Promise<{ changes: number }> {
    if (this.failSql !== undefined && sql.includes(this.failSql)) throw new Error('SQLite test failure');
    return this.native.prepare(sql).run(...flatten(parameters));
  }

  async withExclusiveTransactionAsync(task: (database: TestDatabase) => Promise<void>): Promise<void> {
    this.native.exec('BEGIN IMMEDIATE;');
    try {
      await task(this);
      this.native.exec('COMMIT;');
    } catch (error) {
      this.native.exec('ROLLBACK;');
      throw error;
    }
  }

  close(): void {
    this.native.close();
  }
}

function flatten(parameters: unknown[]): unknown[] {
  return parameters.length === 1 && Array.isArray(parameters[0]) ? parameters[0] : parameters;
}

function page(data: components['schemas']['SyncChange'][], nextCursor: string | null, hasMore = false) {
  return { data, meta: { requestId: 'request-1', nextCursor, hasMore } } as components['schemas']['SyncChangeListEnvelope'];
}

function operation(id: string, createdAt = '2026-08-16T00:00:00Z'): components['schemas']['SyncOperation'] {
  return {
    operationId: id,
    idempotencyKey: `key-${id}-long-enough`,
    entityType: 'TRANSACTION',
    entityId: `entity-${id}`,
    operationType: 'CREATE',
    baseVersion: null,
    payloadVersion: 1,
    payload: {
      type: 'EXPENSE',
      businessAt: '2026-08-16T00:00:00Z',
      businessDate: '2026-08-16',
      timezone: 'Asia/Shanghai',
      accountId: 'account-1',
      amount: '1.00',
      currency: 'CNY',
      categoryId: 'category-1',
    },
    createdAt,
  };
}

function change(sequence: number, entityId = `entity-${sequence}`): components['schemas']['SyncChange'] {
  return {
    sequence,
    entityType: 'TRANSACTION',
    entityId,
    entityVersion: 1,
    changeType: 'UPSERT',
    payloadVersion: 1,
    payload: { marker: entityId },
  };
}

function applied(operationId: string): components['schemas']['SyncOperationResult'] {
  return { operationId, status: 'APPLIED', entityId: `server-${operationId}`, entityVersion: 1 };
}

function duplicate(operationId: string): components['schemas']['SyncOperationResult'] {
  return { operationId, status: 'DUPLICATE', entityId: `server-${operationId}`, entityVersion: 1 };
}

function conflict(operationId: string): Extract<components['schemas']['SyncOperationResult'], { status: 'CONFLICT' }> {
  return {
    operationId,
    status: 'CONFLICT',
    error: {
      type: 'https://ziji.app/problems/version-conflict',
      title: '版本冲突',
      status: 409,
      code: 'VERSION_CONFLICT',
      requestId: 'request-1',
      versionConflict: { currentVersion: 2, currentEtag: '"2"', resourceLocation: '/api/v1/transactions/entity-1' },
    },
  };
}

function rejected(operationId: string): Extract<components['schemas']['SyncOperationResult'], { status: 'REJECTED' }> {
  return {
    operationId,
    status: 'REJECTED',
    error: { type: 'https://ziji.app/problems/business-rule-violation', title: '拒绝', status: 422, code: 'BUSINESS_RULE_VIOLATION', requestId: 'request-1' },
  };
}

function retryable(operationId: string): Extract<components['schemas']['SyncOperationResult'], { status: 'RETRYABLE' }> {
  return {
    operationId,
    status: 'RETRYABLE',
    retryAfterSeconds: 5,
    error: { type: 'https://ziji.app/problems/internal-error', title: '暂时失败', status: 500, code: 'INTERNAL_ERROR', requestId: 'request-1' },
  };
}

class FakeApi implements MobileSyncApiClient {
  readonly changes: components['schemas']['SyncChangeListEnvelope'][] = [];
  readonly requestedCursors: (string | null)[] = [];
  readonly sent: components['schemas']['ApplySyncOperationsRequest'][] = [];
  applyResult: components['schemas']['SyncOperationResultsEnvelope'] = { data: { results: [] }, meta: { requestId: 'request-1' } };
  applyResults: components['schemas']['SyncOperationResultsEnvelope'][] = [];
  applyError: Error | null = null;
  throwOnChangeCall: number | null = null;
  private changeCalls = 0;

  async listSyncChanges(cursor: string | null): Promise<components['schemas']['SyncChangeListEnvelope']> {
    this.requestedCursors.push(cursor);
    this.changeCalls += 1;
    if (this.throwOnChangeCall === this.changeCalls) throw new Error('network');
    return this.changes.shift() ?? page([], null);
  }

  async applySyncOperations(request: components['schemas']['ApplySyncOperationsRequest']) {
    this.sent.push(request);
    if (this.applyError !== null) throw this.applyError;
    return this.applyResults.shift() ?? this.applyResult;
  }
}

async function assertMalformedResultRemainsRetryable(result: unknown, operationId: string): Promise<void> {
  const database = new TestDatabase();
  const api = new FakeApi();
  const queued = operation(operationId);
  await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
  await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued, '2026-08-16T00:00:00Z');
  api.applyResult = { data: { results: [result as components['schemas']['SyncOperationResult']] }, meta: { requestId: 'request-1' } };
  try {
    await expect(pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:00Z')).rejects.toThrow(SyncProtocolError);
    await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', operationId)).resolves.toMatchObject({
      state: 'PENDING', retryAfterAt: '2026-08-16T00:00:05.000Z',
    });
    await expect(getSyncConflict(database as unknown as SQLite.SQLiteDatabase, 'user-a', operationId)).resolves.toBeNull();
  } finally {
    database.close();
  }
}

describe('sync coordinator', () => {
  it('先拉完多页，并在同一 SQLite 事务中提交 change 与 cursor 后再推送', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', true), page([change(2)], 'cursor-2', false));
    const queued = operation('pull-push');
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued, '2026-08-16T00:00:00Z');
    api.applyResult = { data: { results: [applied(queued.operationId)] }, meta: { requestId: 'request-1' } };

    try {
      await synchronize('user-a', { database: database as unknown as SQLite.SQLiteDatabase, api, deviceId: 'device-a', now: () => '2026-08-16T00:00:00Z' });
      expect(api.sent).toHaveLength(1);
      expect(api.sent[0].operations[0].operationId).toBe(queued.operationId);
      expect(api.sent[0].operations[0]).not.toHaveProperty('state');
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toMatchObject({ sequence: 1 });
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-2')).resolves.toMatchObject({ sequence: 2 });
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { userId: 'user-a', scope: 'default' })).resolves.toBe('cursor-2');
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('APPLIED、DUPLICATE、CONFLICT、REJECTED、RETRYABLE 按 operationId 落库且 RETRYABLE 等待五秒', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    const operations = ['applied', 'duplicate', 'conflict', 'rejected', 'retryable'].map((id) => operation(id));
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    for (const item of operations) await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', item, '2026-08-16T00:00:00Z');
    api.applyResult = { data: { results: [retryable('retryable'), rejected('rejected'), conflict('conflict'), duplicate('duplicate'), applied('applied')] }, meta: { requestId: 'request-1' } };

    try {
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:00Z');
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'applied')).resolves.toBeNull();
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'duplicate')).resolves.toBeNull();
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'conflict')).resolves.toMatchObject({ state: 'CONFLICT' });
      await expect(getSyncConflict(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'conflict')).resolves.not.toBeNull();
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'rejected')).resolves.toMatchObject({ state: 'REJECTED' });
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'retryable')).resolves.toMatchObject({ state: 'PENDING', retryAfterAt: '2026-08-16T00:00:05.000Z' });
    } finally {
      database.close();
    }
  });

  it('响应丢失、SENDING 崩溃和畸形结果均回到 PENDING，不立即热重试', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    const queued = operation('network');
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued, '2026-08-16T00:00:00Z');
    await markPendingSending(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId, '2026-08-16T00:00:00Z');
    api.applyError = new Error('network');

    try {
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:00Z');
      expect(api.sent).toHaveLength(0);
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toMatchObject({ state: 'PENDING', retryAfterAt: '2026-08-16T00:00:05.000Z' });

      api.applyError = null;
      api.applyError = new Error('network');
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:05.000Z');
      expect(api.sent).toHaveLength(1);
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toMatchObject({ state: 'PENDING', retryAfterAt: '2026-08-16T00:00:10.000Z' });

      api.applyError = null;
      api.applyResult = { data: { results: [{ operationId: 'unknown', status: 'APPLIED', entityId: 'x', entityVersion: 1 }] }, meta: { requestId: 'request-1' } };
      await expect(pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:10.000Z')).rejects.toThrow(SyncProtocolError);
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toMatchObject({ state: 'PENDING' });

      api.applyResult = { data: { results: [duplicate(queued.operationId)] }, meta: { requestId: 'request-1' } };
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:15.000Z');
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('游标或 SQLite 页面事务失败时不推进确认游标，也不留下半页缓存', async () => {
    const database = new TestDatabase('sync_cursor');
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', false));
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow();
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('批量最多 100 条、按 createdAt/operationId 稳定排序，并隔离其他用户队列', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    const operations = Array.from({ length: 101 }, (_, index) => operation(`op-${String(index).padStart(3, '0')}`, '2026-08-16T00:00:00Z'));
    const otherUserOperation = operation('other-user');
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    for (const item of operations) await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', item, '2026-08-16T00:00:00Z');
    await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-b', otherUserOperation, '2026-08-16T00:00:00Z');
    api.applyResults.push(
      { data: { results: operations.slice(0, 100).map((item) => applied(item.operationId)) }, meta: { requestId: 'request-1' } },
      { data: { results: [applied(operations[100].operationId)] }, meta: { requestId: 'request-1' } },
    );

    try {
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:00Z');
      expect(api.sent.map((request) => request.operations.length)).toEqual([100, 1]);
      expect(api.sent[0].operations[0].operationId).toBe('op-000');
      expect(api.sent[1].operations[0].operationId).toBe('op-100');
      await expect(listPendingOperations(database as unknown as SQLite.SQLiteDatabase, 'user-b')).resolves.toHaveLength(1);
    } finally {
      database.close();
    }
  });

  it('重复分页游标 fail closed，已确认页保留但不会应用下一页', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', true), page([change(2)], 'cursor-1', false));
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow(SyncProtocolError);
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toMatchObject({ sequence: 1 });
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-2')).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it.each([
    ['REJECTED 缺少 type', () => { const result = rejected('malformed'); delete (result.error as Record<string, unknown>).type; return result; }],
    ['REJECTED 缺少 title', () => { const result = rejected('malformed'); delete (result.error as Record<string, unknown>).title; return result; }],
    ['REJECTED status 类型错误', () => { const result = rejected('malformed'); (result.error as Record<string, unknown>).status = '422'; return result; }],
    ['REJECTED 缺少 requestId', () => { const result = rejected('malformed'); delete (result.error as Record<string, unknown>).requestId; return result; }],
    ['REJECTED fieldErrors 字段类型错误', () => { const result = rejected('malformed'); (result.error as Record<string, unknown>).fieldErrors = [{ field: 'amount', code: 'INVALID', message: 1 }]; return result; }],
    ['CONFLICT ETag 与版本不一致', () => { const result = conflict('malformed'); (result.error.versionConflict as Record<string, unknown>).currentEtag = '"3"'; return result; }],
    ['RETRYABLE 缺少 title', () => { const result = retryable('malformed'); delete (result.error as Record<string, unknown>).title; return result; }],
    ['RETRYABLE code/status 不匹配', () => { const result = retryable('malformed'); (result.error as Record<string, unknown>).code = 'INTERNAL_ERROR'; return result; }],
    ['RETRYABLE 重试秒数不是五秒', () => ({ ...retryable('malformed'), retryAfterSeconds: 4 })],
  ])('%s 时 fail closed 并保留 PENDING', async (_name, makeResult) => {
    await assertMalformedResultRemainsRetryable(makeResult(), `malformed-${_name}`);
  });

  it.each([
    ['nextCursor 缺失', { data: [change(1)], meta: { requestId: 'request-1', hasMore: false } }],
    ['nextCursor 为数字', { data: [], meta: { requestId: 'request-1', nextCursor: 1, hasMore: false } }],
    ['nextCursor 为对象', { data: [], meta: { requestId: 'request-1', nextCursor: {}, hasMore: false } }],
    ['空页缺失 nextCursor', { data: [], meta: { requestId: 'request-1', hasMore: false } }],
    ['hasMore=true 缺少可推进游标', { data: [change(1)], meta: { requestId: 'request-1', nextCursor: null, hasMore: true } }],
  ])('%s 时不应用页面或推进游标', async (_name, invalidPage) => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(invalidPage as unknown as components['schemas']['SyncChangeListEnvelope']);
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow(SyncProtocolError);
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { userId: 'user-a', scope: 'default' })).resolves.toBeNull();
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('畸形后续游标保留已确认游标且不写入该页缓存', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', true), { data: [change(2)], meta: { requestId: 'request-1', nextCursor: 2, hasMore: false } } as unknown as components['schemas']['SyncChangeListEnvelope']);
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow(SyncProtocolError);
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { userId: 'user-a', scope: 'default' })).resolves.toBe('cursor-1');
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toMatchObject({ sequence: 1 });
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-2')).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('空页缺失 nextCursor 时不清空既有确认游标', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', true), { data: [], meta: { requestId: 'request-1', hasMore: false } } as unknown as components['schemas']['SyncChangeListEnvelope']);
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow(SyncProtocolError);
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { userId: 'user-a', scope: 'default' })).resolves.toBe('cursor-1');
      await expect(getCachedEntity(database as unknown as SQLite.SQLiteDatabase, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toMatchObject({ sequence: 1 });
    } finally {
      database.close();
    }
  });

  it('重启后从已确认 cursor 拉取剩余页面', async () => {
    const database = new TestDatabase();
    const api = new FakeApi();
    api.changes.push(page([change(1)], 'cursor-1', true), page([change(2)], 'cursor-2', false));
    api.throwOnChangeCall = 2;
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

    try {
      await expect(pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:00Z')).rejects.toThrow('network');
      api.throwOnChangeCall = null;
      await pullChanges({ userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, () => '2026-08-16T00:00:01Z');
      expect(api.requestedCursors).toEqual([null, 'cursor-1', 'cursor-1']);
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { userId: 'user-a', scope: 'default' })).resolves.toBe('cursor-2');
    } finally {
      database.close();
    }
  });

  it.each([429, 502, 503])('HTTP %i 恢复为持久 PENDING，且不在同轮热重试', async (status) => {
    const database = new TestDatabase();
    const api = new FakeApi();
    const queued = operation(`http-${status}`);
    await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
    await enqueuePendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued, '2026-08-16T00:00:00Z');
    api.applyError = new ApiClientError({ type: 'about:blank', title: 'HTTP failure', status, code: 'HTTP_ERROR', requestId: 'request-1' });

    try {
      await pushOperations('user-a', { userId: 'user-a', scope: 'default' }, database as unknown as SQLite.SQLiteDatabase, api, 'device-a', () => '2026-08-16T00:00:00Z');
      expect(api.sent).toHaveLength(1);
      await expect(getPendingOperation(database as unknown as SQLite.SQLiteDatabase, 'user-a', queued.operationId)).resolves.toMatchObject({
        state: 'PENDING', retryAfterAt: '2026-08-16T00:00:05.000Z',
      });
    } finally {
      database.close();
    }
  });
});
