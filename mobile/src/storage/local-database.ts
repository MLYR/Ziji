import * as SQLite from 'expo-sqlite';

import type { components } from '@ziji/api-types';

const DATABASE_NAME = 'ziji-cache.db';

export const LOCAL_DATABASE_SCHEMA_VERSION = 2;

type SyncChange = components['schemas']['SyncChange'];
type SyncOperation = components['schemas']['SyncOperation'];
type SyncProblem = components['schemas']['Problem'];

export type PendingOperationState = 'PENDING' | 'SENDING' | 'CONFLICT' | 'REJECTED';

export interface LocalSyncScope {
  scope: string;
  userId: string;
}

export interface CachedEntity extends SyncChange {
  updatedAt: string;
}

export type PendingSyncOperation = SyncOperation & {
  state: PendingOperationState;
  updatedAt: string;
};

export interface SyncConflict {
  createdAt: string;
  operationId: string;
  problem: SyncProblem;
}

interface CachedEntityRow {
  change_type: SyncChange['changeType'];
  entity_id: string;
  entity_type: string;
  entity_version: number;
  payload_json: string | null;
  payload_version: 1;
  sequence: number;
  updated_at: string;
}

interface PendingOperationRow {
  base_version: number | null;
  created_at: string;
  entity_id: string;
  entity_type: string;
  idempotency_key: string;
  operation_id: string;
  operation_type: string;
  payload_json: string;
  payload_version: 1;
  state: PendingOperationState;
  updated_at: string;
}

interface SyncConflictRow {
  created_at: string;
  operation_id: string;
  problem_json: string;
}

export interface LocalDatabaseSecurity {
  prepare(database: SQLite.SQLiteDatabase): Promise<void>;
}

export const platformDatabaseSecurity: LocalDatabaseSecurity = {
  async prepare(database) {
    // Expo managed 基座先依赖系统沙箱并启用 secure_delete；生产加密适配器在原生构建门禁替换此实现。
    await database.execAsync('PRAGMA secure_delete = ON;');
  },
};

let databasePromise: Promise<SQLite.SQLiteDatabase> | undefined;

const allowedPreviousStates: Record<PendingOperationState, readonly PendingOperationState[]> = {
  CONFLICT: ['PENDING', 'SENDING'],
  PENDING: ['SENDING'],
  REJECTED: ['PENDING', 'SENDING'],
  SENDING: ['PENDING'],
};

function assertUserId(userId: string): void {
  if (userId.trim().length === 0) {
    throw new Error('同步存储必须提供当前用户 ID。');
  }
}

function assertScope(scope: LocalSyncScope): void {
  assertUserId(scope.userId);

  if (scope.scope.trim().length === 0) {
    throw new Error('同步存储必须提供同步范围。');
  }
}

function parseJson<T>(value: string): T {
  return JSON.parse(value) as T;
}

function toCachedEntity(row: CachedEntityRow): CachedEntity {
  return {
    changeType: row.change_type,
    entityId: row.entity_id,
    entityType: row.entity_type,
    entityVersion: row.entity_version,
    ...(row.payload_json === null ? {} : { payload: parseJson<SyncChange['payload']>(row.payload_json) }),
    payloadVersion: row.payload_version,
    sequence: row.sequence,
    updatedAt: row.updated_at,
  };
}

function toPendingOperation(row: PendingOperationRow): PendingSyncOperation {
  // SQLite 行是动态数据，读取时必须恢复生成联合体的不变量，不能绕过服务器契约。
  if (row.entity_type !== 'TRANSACTION' || row.payload_version !== 1) {
    throw new Error('本地待上传操作不再受当前同步契约支持。');
  }

  const common = {
    createdAt: row.created_at,
    entityId: row.entity_id,
    entityType: 'TRANSACTION' as const,
    idempotencyKey: row.idempotency_key,
    operationId: row.operation_id,
    payloadVersion: 1 as const,
    state: row.state,
    updatedAt: row.updated_at,
  };
  const payload = parsePendingPayload(row.payload_json);

  switch (row.operation_type) {
    case 'CREATE':
      if (row.base_version !== null || !isSyncCreatePayload(payload)) {
        throw new Error('本地 CREATE 操作不符合当前同步契约。');
      }
      return { ...common, baseVersion: null, operationType: 'CREATE', payload };
    case 'UPDATE':
      if (!isPositiveInteger(row.base_version) || !isSyncUpdatePayload(payload)) {
        throw new Error('本地 UPDATE 操作不符合当前同步契约。');
      }
      return { ...common, baseVersion: row.base_version, operationType: 'UPDATE', payload };
    case 'REVERSE':
      if (!isPositiveInteger(row.base_version) || !isReasonPayload(payload)) {
        throw new Error('本地 REVERSE 操作不符合当前同步契约。');
      }
      return { ...common, baseVersion: row.base_version, operationType: 'REVERSE', payload };
    default:
      throw new Error('本地待上传操作不再受当前同步契约支持。');
  }
}

type SyncCreateOperation = Extract<SyncOperation, { operationType: 'CREATE' }>;
type SyncUpdateOperation = Extract<SyncOperation, { operationType: 'UPDATE' }>;
type SyncReverseOperation = Extract<SyncOperation, { operationType: 'REVERSE' }>;

function parsePendingPayload(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error('本地待上传操作载荷不是有效 JSON。');
  }
}

function isSyncCreatePayload(value: unknown): value is SyncCreateOperation['payload'] {
  return isRecord(value)
    && !Object.hasOwn(value, 'id')
    && isSyncCreateType(value.type);
}

function isSyncUpdatePayload(value: unknown): value is SyncUpdateOperation['payload'] {
  return isRecord(value)
    && typeof value.reason === 'string'
    && isSyncCreatePayload(value.replacement);
}

function isReasonPayload(value: unknown): value is SyncReverseOperation['payload'] {
  return isRecord(value)
    && typeof value.reason === 'string';
}

function isSyncCreateType(value: unknown): value is 'INCOME' | 'EXPENSE' | 'REFUND' | 'TRANSFER' {
  return value === 'INCOME' || value === 'EXPENSE' || value === 'REFUND' || value === 'TRANSFER';
}

function isPositiveInteger(value: number | null): value is number {
  return value !== null && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function createV2Schema(database: SQLite.SQLiteDatabase): Promise<void> {
  // SQLite 只保存缓存和同步控制信息，绝不成为余额、持仓或账务事实源。
  await database.execAsync(`
    -- 已同步实体缓存：按当前认证用户隔离，不保存余额、分录或持仓权威事实。
    CREATE TABLE IF NOT EXISTS cached_entities (
      user_id TEXT NOT NULL,
      entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL,
      entity_version INTEGER NOT NULL CHECK (entity_version >= 1),
      change_type TEXT NOT NULL CHECK (change_type IN ('UPSERT', 'TOMBSTONE', 'ACCESS_REVOKED', 'BOOTSTRAP')),
      payload_version INTEGER NOT NULL CHECK (payload_version = 1),
      payload_json TEXT,
      sequence INTEGER NOT NULL CHECK (sequence >= 1),
      updated_at TEXT NOT NULL,
      -- 同一用户同一实体只保留服务端最后确认的缓存副本。
      PRIMARY KEY (user_id, entity_type, entity_id)
    );

    -- 已确认同步游标：用户与同步范围共同隔离，禁止跨用户复用不透明 cursor。
    CREATE TABLE IF NOT EXISTS sync_cursor (
      user_id TEXT NOT NULL,
      scope TEXT NOT NULL,
      cursor TEXT,
      updated_at TEXT NOT NULL,
      PRIMARY KEY (user_id, scope)
    );

    -- 待上传操作队列：完整保存生成类型 SyncOperation 的字段，仅表示本地同步流程。
    CREATE TABLE IF NOT EXISTS pending_operations (
      user_id TEXT NOT NULL,
      operation_id TEXT NOT NULL,
      idempotency_key TEXT NOT NULL,
      entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL,
      operation_type TEXT NOT NULL CHECK (operation_type IN ('CREATE', 'UPDATE', 'REVERSE', 'ARCHIVE')),
      base_version INTEGER CHECK (base_version IS NULL OR base_version >= 1),
      payload_version INTEGER NOT NULL CHECK (payload_version = 1),
      payload_json TEXT NOT NULL,
      state TEXT NOT NULL CHECK (state IN ('PENDING', 'SENDING', 'CONFLICT', 'REJECTED')),
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      -- 同一用户内操作和幂等键均不能重复入队；不同用户彼此隔离。
      PRIMARY KEY (user_id, operation_id),
      UNIQUE (user_id, idempotency_key)
    );

    -- 待上传队列读取索引：保持按用户、流程状态和创建时间的稳定顺序。
    CREATE INDEX IF NOT EXISTS idx_pending_operations_user_state_created_at
      ON pending_operations (user_id, state, created_at, operation_id);

    -- 冲突副本：只保存生成的 Problem 与同用户的本地操作引用，不保存认证材料。
    CREATE TABLE IF NOT EXISTS sync_conflicts (
      user_id TEXT NOT NULL,
      operation_id TEXT NOT NULL,
      problem_json TEXT NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (user_id, operation_id),
      -- 冲突必须对应同一用户域内仍可供后续处理的本地操作。
      FOREIGN KEY (user_id, operation_id) REFERENCES pending_operations (user_id, operation_id)
    );
  `);
}

async function migrateV1ToV2(database: SQLite.SQLiteDatabase): Promise<void> {
  await database.withExclusiveTransactionAsync(async (transaction) => {
    // v1 没有可信用户归属；只改名隔离，正式 v2 repository 永不读取 legacy 表。
    await transaction.execAsync(`
      ALTER TABLE sync_state RENAME TO legacy_v1_sync_state;
      ALTER TABLE pending_commands RENAME TO legacy_v1_pending_commands;
      ALTER TABLE sync_conflicts RENAME TO legacy_v1_sync_conflicts;
    `);
    await createV2Schema(transaction);
    await transaction.execAsync(`PRAGMA user_version = ${LOCAL_DATABASE_SCHEMA_VERSION};`);
  });
}

export async function migrateLocalDatabase(database: SQLite.SQLiteDatabase): Promise<void> {
  await platformDatabaseSecurity.prepare(database);
  await database.execAsync('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');

  const version = (await database.getFirstAsync<{ user_version: number }>('PRAGMA user_version;'))?.user_version ?? 0;

  if (version > LOCAL_DATABASE_SCHEMA_VERSION) {
    throw new Error(`本地数据库版本 ${version} 高于当前支持版本 ${LOCAL_DATABASE_SCHEMA_VERSION}。`);
  }

  if (version === 0) {
    await database.withExclusiveTransactionAsync(async (transaction) => {
      await createV2Schema(transaction);
      await transaction.execAsync(`PRAGMA user_version = ${LOCAL_DATABASE_SCHEMA_VERSION};`);
    });
    return;
  }

  if (version === 1) {
    await migrateV1ToV2(database);
  }
}

export async function getSyncCursor(database: SQLite.SQLiteDatabase, scope: LocalSyncScope): Promise<string | null> {
  assertScope(scope);

  const row = await database.getFirstAsync<{ cursor: string | null }>(
    'SELECT cursor FROM sync_cursor WHERE user_id = ? AND scope = ?;',
    [scope.userId, scope.scope],
  );

  return row?.cursor ?? null;
}

export async function saveSyncCursor(database: SQLite.SQLiteDatabase, scope: LocalSyncScope, cursor: string | null, updatedAt: string): Promise<void> {
  assertScope(scope);

  await database.runAsync(
    `INSERT INTO sync_cursor (user_id, scope, cursor, updated_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT (user_id, scope) DO UPDATE SET cursor = excluded.cursor, updated_at = excluded.updated_at;`,
    [scope.userId, scope.scope, cursor, updatedAt],
  );
}

export async function getCachedEntity(database: SQLite.SQLiteDatabase, userId: string, entityType: string, entityId: string): Promise<CachedEntity | null> {
  assertUserId(userId);

  const row = await database.getFirstAsync<CachedEntityRow>(
    `SELECT entity_type, entity_id, entity_version, change_type, payload_version, payload_json, sequence, updated_at
     FROM cached_entities
     WHERE user_id = ? AND entity_type = ? AND entity_id = ?;`,
    [userId, entityType, entityId],
  );

  return row === null ? null : toCachedEntity(row);
}

export async function saveCachedEntity(database: SQLite.SQLiteDatabase, userId: string, change: SyncChange, updatedAt: string): Promise<void> {
  assertUserId(userId);

  await database.runAsync(
    `INSERT INTO cached_entities (
       user_id, entity_type, entity_id, entity_version, change_type, payload_version, payload_json, sequence, updated_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (user_id, entity_type, entity_id) DO UPDATE SET
       entity_version = excluded.entity_version,
       change_type = excluded.change_type,
       payload_version = excluded.payload_version,
       payload_json = excluded.payload_json,
       sequence = excluded.sequence,
       updated_at = excluded.updated_at
     -- 服务端 sequence 严格单调；同序重放只读幂等，不能改写缓存。
     WHERE excluded.sequence > cached_entities.sequence;`,
    [
      userId,
      change.entityType,
      change.entityId,
      change.entityVersion,
      change.changeType,
      change.payloadVersion,
      change.payload === undefined ? null : JSON.stringify(change.payload),
      change.sequence,
      updatedAt,
    ],
  );
}

export async function enqueuePendingOperation(database: SQLite.SQLiteDatabase, userId: string, operation: SyncOperation, updatedAt: string): Promise<void> {
  assertUserId(userId);

  await database.runAsync(
    `INSERT INTO pending_operations (
       user_id, operation_id, idempotency_key, entity_type, entity_id, operation_type, base_version,
       payload_version, payload_json, state, created_at, updated_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?);`,
    [
      userId,
      operation.operationId,
      operation.idempotencyKey,
      operation.entityType,
      operation.entityId,
      operation.operationType,
      operation.baseVersion ?? null,
      operation.payloadVersion,
      JSON.stringify(operation.payload),
      operation.createdAt,
      updatedAt,
    ],
  );
}

export async function getPendingOperation(database: SQLite.SQLiteDatabase, userId: string, operationId: string): Promise<PendingSyncOperation | null> {
  assertUserId(userId);

  const row = await database.getFirstAsync<PendingOperationRow>(
    `SELECT operation_id, idempotency_key, entity_type, entity_id, operation_type, base_version,
       payload_version, payload_json, state, created_at, updated_at
     FROM pending_operations
     WHERE user_id = ? AND operation_id = ?;`,
    [userId, operationId],
  );

  return row === null ? null : toPendingOperation(row);
}

export async function listPendingOperations(database: SQLite.SQLiteDatabase, userId: string): Promise<PendingSyncOperation[]> {
  assertUserId(userId);

  const rows = await database.getAllAsync<PendingOperationRow>(
    `SELECT operation_id, idempotency_key, entity_type, entity_id, operation_type, base_version,
       payload_version, payload_json, state, created_at, updated_at
     FROM pending_operations
     WHERE user_id = ?
     ORDER BY created_at ASC, operation_id ASC;`,
    [userId],
  );

  return rows.map(toPendingOperation);
}

export async function updatePendingOperationState(
  database: SQLite.SQLiteDatabase,
  userId: string,
  operationId: string,
  state: PendingOperationState,
  updatedAt: string,
): Promise<void> {
  assertUserId(userId);

  const previousStates = allowedPreviousStates[state];
  if (previousStates.length === 0) {
    throw new Error(`不允许将本地操作流转为 ${state}。`);
  }

  const placeholders = previousStates.map(() => '?').join(', ');
  const result = await database.runAsync(
    `UPDATE pending_operations
     SET state = ?, updated_at = ?
     WHERE user_id = ? AND operation_id = ? AND state IN (${placeholders});`,
    [state, updatedAt, userId, operationId, ...previousStates],
  );

  // 条件更新把状态边界与用户边界放在同一条语句，避免后续同步循环读写竞态绕过约束。
  if (result.changes !== 1) {
    throw new Error(`不允许将本地操作 ${operationId} 流转为 ${state}。`);
  }
}

export async function saveSyncConflict(
  database: SQLite.SQLiteDatabase,
  userId: string,
  operationId: string,
  problem: SyncProblem,
  createdAt: string,
): Promise<void> {
  await database.withExclusiveTransactionAsync(async (transaction) => {
    // 冲突状态与 Problem 副本必须原子保存，避免 UI 看到无处理材料的 CONFLICT 操作。
    await updatePendingOperationState(transaction, userId, operationId, 'CONFLICT', createdAt);
    await transaction.runAsync(
      `INSERT INTO sync_conflicts (user_id, operation_id, problem_json, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (user_id, operation_id) DO UPDATE SET
         problem_json = excluded.problem_json,
         created_at = excluded.created_at;`,
      [userId, operationId, JSON.stringify(problem), createdAt],
    );
  });
}

export async function getSyncConflict(database: SQLite.SQLiteDatabase, userId: string, operationId: string): Promise<SyncConflict | null> {
  assertUserId(userId);

  const row = await database.getFirstAsync<SyncConflictRow>(
    `SELECT operation_id, problem_json, created_at
     FROM sync_conflicts
     WHERE user_id = ? AND operation_id = ?;`,
    [userId, operationId],
  );

  return row === null
    ? null
    : {
        createdAt: row.created_at,
        operationId: row.operation_id,
        problem: parseJson<SyncProblem>(row.problem_json),
      };
}

async function openAndMigrateDatabase(): Promise<SQLite.SQLiteDatabase> {
  const database = await SQLite.openDatabaseAsync(DATABASE_NAME);
  await migrateLocalDatabase(database);

  return database;
}

export function getLocalDatabase(): Promise<SQLite.SQLiteDatabase> {
  // 复用同一初始化 Promise，防止并发启动重复执行迁移。
  databasePromise ??= openAndMigrateDatabase();
  return databasePromise;
}
