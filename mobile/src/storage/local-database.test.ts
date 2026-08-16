jest.mock('expo-sqlite', () => ({ openDatabaseAsync: jest.fn() }));

import type * as SQLite from 'expo-sqlite';

import {
  LOCAL_DATABASE_SCHEMA_VERSION,
  enqueuePendingOperation,
  getCachedEntity,
  getPendingOperation,
  getSyncConflict,
  getSyncCursor,
  listPendingOperations,
  migrateLocalDatabase,
  saveCachedEntity,
  saveSyncConflict,
  saveSyncCursor,
  updatePendingOperationState,
} from './local-database';

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
    return this.native.prepare(sql).all(...flattenParameters(parameters)) as T[];
  }

  async getFirstAsync<T>(sql: string, ...parameters: unknown[]): Promise<T | null> {
    return (this.native.prepare(sql).get(...flattenParameters(parameters)) as T | undefined) ?? null;
  }

  async runAsync(sql: string, ...parameters: unknown[]): Promise<{ changes: number }> {
    if (this.failSql !== undefined && sql.includes(this.failSql)) {
      throw new Error('注入的 SQLite 写入失败。');
    }

    return this.native.prepare(sql).run(...flattenParameters(parameters));
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

function flattenParameters(parameters: unknown[]): unknown[] {
  return parameters.length === 1 && Array.isArray(parameters[0]) ? parameters[0] : parameters;
}

function createDatabase(failSql?: string): TestDatabase {
  return new TestDatabase(failSql);
}

async function readSchemaVersion(database: TestDatabase): Promise<number> {
  const version = await database.getFirstAsync<{ user_version: number }>('PRAGMA user_version;');
  return version?.user_version ?? 0;
}

describe('local database migration', () => {
  it('为全新库建立仅含缓存和同步控制的 v2 schema', async () => {
    const database = createDatabase();

    try {
      await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

      const tables = await database.getAllAsync<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name;");
      const schema = await database.getAllAsync<{ sql: string }>("SELECT sql FROM sqlite_master WHERE type = 'table';");

      expect(await readSchemaVersion(database)).toBe(LOCAL_DATABASE_SCHEMA_VERSION);
      expect(tables.map((table) => table.name)).toEqual(expect.arrayContaining(['cached_entities', 'pending_operations', 'sync_conflicts', 'sync_cursor']));
      expect(tables.map((table) => table.name)).not.toContain('ledger_entries');
      expect(JSON.stringify(schema)).not.toMatch(/access_token|refresh_token|authorization|ledger_entries|balance|position/i);
    } finally {
      database.close();
    }
  });

  it('将 v1 无归属同步数据隔离到 legacy 表而不向 v2 用户域暴露', async () => {
    const database = createDatabase();

    try {
      await database.execAsync(`
        CREATE TABLE sync_state (scope TEXT PRIMARY KEY NOT NULL, cursor TEXT, updated_at TEXT NOT NULL);
        CREATE TABLE pending_commands (id TEXT PRIMARY KEY NOT NULL, operation_id TEXT NOT NULL, idempotency_key TEXT NOT NULL, payload_json TEXT NOT NULL, state TEXT NOT NULL, created_at TEXT NOT NULL);
        CREATE TABLE sync_conflicts (id TEXT PRIMARY KEY NOT NULL, command_id TEXT NOT NULL, problem_json TEXT NOT NULL, created_at TEXT NOT NULL);
        INSERT INTO sync_state VALUES ('default', 'legacy-cursor', '2026-08-16T00:00:00Z');
        INSERT INTO pending_commands VALUES ('legacy-command', 'legacy-operation', 'legacy-key', '{}', 'PENDING', '2026-08-16T00:00:00Z');
        INSERT INTO sync_conflicts VALUES ('legacy-conflict', 'legacy-command', '{}', '2026-08-16T00:00:00Z');
        PRAGMA user_version = 1;
      `);

      await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

      const legacyCursor = await database.getFirstAsync<{ cursor: string }>('SELECT cursor FROM legacy_v1_sync_state;');
      const legacyPending = await database.getFirstAsync<{ operation_id: string }>('SELECT operation_id FROM legacy_v1_pending_commands;');
      const legacyConflict = await database.getFirstAsync<{ command_id: string }>('SELECT command_id FROM legacy_v1_sync_conflicts;');

      expect(await readSchemaVersion(database)).toBe(LOCAL_DATABASE_SCHEMA_VERSION);
      expect(legacyCursor?.cursor).toBe('legacy-cursor');
      expect(legacyPending?.operation_id).toBe('legacy-operation');
      expect(legacyConflict?.command_id).toBe('legacy-command');
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { scope: 'default', userId: 'user-b' })).resolves.toBeNull();
      await expect(listPendingOperations(database as unknown as SQLite.SQLiteDatabase, 'user-b')).resolves.toEqual([]);

      await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

      await expect(database.getFirstAsync<{ cursor: string }>('SELECT cursor FROM legacy_v1_sync_state;')).resolves.toEqual({ cursor: 'legacy-cursor' });
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { scope: 'default', userId: 'user-b' })).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('重复迁移不会再次迁移或破坏 v2 表', async () => {
    const database = createDatabase();

    try {
      await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);
      await saveSyncCursor(database as unknown as SQLite.SQLiteDatabase, { scope: 'default', userId: 'user-a' }, 'cursor-a', '2026-08-16T00:00:00Z');

      await migrateLocalDatabase(database as unknown as SQLite.SQLiteDatabase);

      expect(await readSchemaVersion(database)).toBe(LOCAL_DATABASE_SCHEMA_VERSION);
      await expect(getSyncCursor(database as unknown as SQLite.SQLiteDatabase, { scope: 'default', userId: 'user-a' })).resolves.toBe('cursor-a');
    } finally {
      database.close();
    }
  });
});

describe('local sync storage', () => {
  const operation = {
    baseVersion: null,
    createdAt: '2026-08-16T00:00:00Z',
    entityId: 'entity-1',
    entityType: 'TRANSACTION' as const,
    idempotencyKey: 'key-1',
    operationId: 'operation-1',
    operationType: 'CREATE' as const,
    payload: { accountId: 'account-1', amount: '10.00', businessDate: '2026-08-16', currency: 'CNY', type: 'EXPENSE' } as never,
    payloadVersion: 1 as const,
  };

  it('按 user_id 隔离 cursor、缓存、队列和冲突', async () => {
    const database = createDatabase();
    const sqlite = database as unknown as SQLite.SQLiteDatabase;

    try {
      await migrateLocalDatabase(sqlite);
      await saveSyncCursor(sqlite, { scope: 'default', userId: 'user-a' }, 'cursor-a', '2026-08-16T00:00:00Z');
      await saveSyncCursor(sqlite, { scope: 'default', userId: 'user-b' }, 'cursor-b', '2026-08-16T00:00:00Z');
      await saveCachedEntity(sqlite, 'user-a', {
        changeType: 'UPSERT',
        entityId: 'entity-1',
        entityType: 'TRANSACTION',
        entityVersion: 1,
        payload: { note: 'only-a' },
        payloadVersion: 1,
        sequence: 2,
      }, '2026-08-16T00:00:00Z');
      await saveCachedEntity(sqlite, 'user-a', {
        changeType: 'UPSERT',
        entityId: 'entity-1',
        entityType: 'TRANSACTION',
        entityVersion: 1,
        payload: { note: 'stale' },
        payloadVersion: 1,
        sequence: 1,
      }, '2026-08-16T00:00:01Z');
      await saveCachedEntity(sqlite, 'user-a', {
        changeType: 'UPSERT',
        entityId: 'entity-1',
        entityType: 'TRANSACTION',
        entityVersion: 2,
        payload: { note: 'same-sequence-must-not-overwrite' },
        payloadVersion: 1,
        sequence: 2,
      }, '2026-08-16T00:00:02Z');
      await enqueuePendingOperation(sqlite, 'user-a', operation, '2026-08-16T00:00:00Z');
      await enqueuePendingOperation(sqlite, 'user-b', operation, '2026-08-16T00:00:00Z');
      await saveSyncConflict(sqlite, 'user-a', operation.operationId, {
        code: 'VERSION_CONFLICT',
        requestId: 'request-1',
        status: 409,
        title: '版本冲突',
        type: 'https://ziji.app/problems/version-conflict',
      }, '2026-08-16T00:00:01Z');

      await expect(getSyncCursor(sqlite, { scope: 'default', userId: 'user-a' })).resolves.toBe('cursor-a');
      await expect(getSyncCursor(sqlite, { scope: 'default', userId: 'user-b' })).resolves.toBe('cursor-b');
      await expect(getCachedEntity(sqlite, 'user-a', 'TRANSACTION', 'entity-1')).resolves.toMatchObject({
        entityVersion: 1,
        payload: { note: 'only-a' },
        sequence: 2,
        updatedAt: '2026-08-16T00:00:00Z',
      });
      await expect(getCachedEntity(sqlite, 'user-b', 'TRANSACTION', 'entity-1')).resolves.toBeNull();
      await expect(listPendingOperations(sqlite, 'user-a')).resolves.toHaveLength(1);
      await expect(listPendingOperations(sqlite, 'user-b')).resolves.toHaveLength(1);
      await expect(getSyncConflict(sqlite, 'user-b', operation.operationId)).resolves.toBeNull();
    } finally {
      database.close();
    }
  });

  it('保存完整 SyncOperation、约束重复键并限制本地状态流转', async () => {
    const database = createDatabase();
    const sqlite = database as unknown as SQLite.SQLiteDatabase;

    try {
      await migrateLocalDatabase(sqlite);
      await enqueuePendingOperation(sqlite, 'user-a', operation, '2026-08-16T00:00:00Z');

      await expect(getPendingOperation(sqlite, 'user-a', operation.operationId)).resolves.toEqual({ ...operation, state: 'PENDING', updatedAt: '2026-08-16T00:00:00Z' });
      await expect(enqueuePendingOperation(sqlite, 'user-a', operation, '2026-08-16T00:00:00Z')).rejects.toThrow();
      await expect(enqueuePendingOperation(sqlite, 'user-a', { ...operation, operationId: 'operation-2' }, '2026-08-16T00:00:00Z')).rejects.toThrow();

      await updatePendingOperationState(sqlite, 'user-a', operation.operationId, 'SENDING', '2026-08-16T00:00:01Z');
      await updatePendingOperationState(sqlite, 'user-a', operation.operationId, 'REJECTED', '2026-08-16T00:00:02Z');
      await expect(updatePendingOperationState(sqlite, 'user-a', operation.operationId, 'SENDING', '2026-08-16T00:00:03Z')).rejects.toThrow('不允许');
    } finally {
      database.close();
    }
  });

  it('冲突仅保存生成 Problem 与现有本地操作，不保存认证材料', async () => {
    const database = createDatabase();
    const sqlite = database as unknown as SQLite.SQLiteDatabase;

    try {
      await migrateLocalDatabase(sqlite);
      await enqueuePendingOperation(sqlite, 'user-a', operation, '2026-08-16T00:00:00Z');
      await saveSyncConflict(sqlite, 'user-a', operation.operationId, {
        code: 'VERSION_CONFLICT',
        requestId: 'request-1',
        status: 409,
        title: '版本冲突',
        type: 'https://ziji.app/problems/version-conflict',
        versionConflict: { currentEtag: '"2"', currentVersion: 2, resourceLocation: '/api/v1/transactions/entity-1' },
      }, '2026-08-16T00:00:01Z');

      await expect(getSyncConflict(sqlite, 'user-a', operation.operationId)).resolves.toEqual({
        createdAt: '2026-08-16T00:00:01Z',
        operationId: operation.operationId,
        problem: {
          code: 'VERSION_CONFLICT',
          requestId: 'request-1',
          status: 409,
          title: '版本冲突',
          type: 'https://ziji.app/problems/version-conflict',
          versionConflict: { currentEtag: '"2"', currentVersion: 2, resourceLocation: '/api/v1/transactions/entity-1' },
        },
      });
      await expect(getPendingOperation(sqlite, 'user-a', operation.operationId)).resolves.toMatchObject({ state: 'CONFLICT' });
    } finally {
      database.close();
    }
  });

  it('冲突副本写入失败时回滚本地操作状态', async () => {
    const database = createDatabase('INSERT INTO sync_conflicts');
    const sqlite = database as unknown as SQLite.SQLiteDatabase;

    try {
      await migrateLocalDatabase(sqlite);
      await enqueuePendingOperation(sqlite, 'user-a', operation, '2026-08-16T00:00:00Z');

      await expect(saveSyncConflict(sqlite, 'user-a', operation.operationId, {
        code: 'VERSION_CONFLICT',
        requestId: 'request-1',
        status: 409,
        title: '版本冲突',
        type: 'https://ziji.app/problems/version-conflict',
      }, '2026-08-16T00:00:01Z')).rejects.toThrow('注入的 SQLite 写入失败');

      await expect(getPendingOperation(sqlite, 'user-a', operation.operationId)).resolves.toMatchObject({ state: 'PENDING' });
      await expect(getSyncConflict(sqlite, 'user-a', operation.operationId)).resolves.toBeNull();
    } finally {
      database.close();
    }
  });
});
