import type * as SQLite from 'expo-sqlite';

import type { components } from '@ziji/api-types';

import type { MobileSyncApiClient } from '../api/api-client';
import {
  deletePendingOperation,
  getSyncCursor,
  listPendingOperations,
  markPendingRetryable,
  markPendingSending,
  recoverSendingOperations,
  saveCachedEntity,
  saveSyncConflictInTransaction,
  saveSyncCursor,
  updatePendingOperationState,
  type LocalSyncScope,
  type PendingSyncOperation,
} from '../storage/local-database';

type SyncChange = components['schemas']['SyncChange'];
type SyncOperation = components['schemas']['SyncOperation'];
type SyncOperationResult = components['schemas']['SyncOperationResult'];
type SyncProblem = components['schemas']['Problem'];

const SYNC_SCOPE = 'default';
const MAX_BATCH_SIZE = 100;
const RETRY_AFTER_SECONDS = 5;

export interface SyncCoordinatorOptions {
  api: MobileSyncApiClient;
  database: SQLite.SQLiteDatabase;
  deviceId: string;
  now?: () => string;
  scope?: string;
}

export class SyncProtocolError extends Error {
  constructor(message = '同步响应结构不符合当前契约。') {
    super(message);
    this.name = 'SyncProtocolError';
  }
}

export async function synchronize(userId: string, options: SyncCoordinatorOptions): Promise<void> {
  const now = options.now ?? (() => new Date().toISOString());
  if (options.deviceId.trim().length === 0) throw new SyncProtocolError('同步设备标识不能为空。');
  const scope: LocalSyncScope = { scope: options.scope ?? SYNC_SCOPE, userId };

  // 拉取先完成并确认游标，再允许推送；拉取协议异常时不会带着未确认游标写入业务操作。
  await pullChanges(scope, options.database, options.api, now);
  await pushOperations(userId, scope, options.database, options.api, options.deviceId, now);
}

export async function pullChanges(
  scope: LocalSyncScope,
  database: SQLite.SQLiteDatabase,
  api: MobileSyncApiClient,
  now: () => string,
): Promise<void> {
  let cursor = await getSyncCursor(database, scope);
  const seenCursors = new Set<string>();

  while (true) {
    const page = await api.listSyncChanges(cursor);
    const { changes, hasMore, nextCursor } = validateChangePage(page);

    if (changes.length > 0 && (nextCursor === null || nextCursor === cursor || seenCursors.has(nextCursor))) {
      throw new SyncProtocolError('同步游标缺失、倒退或重复。');
    }
    if (hasMore && (changes.length === 0 || nextCursor === null || nextCursor === cursor)) {
      throw new SyncProtocolError('分页响应声称仍有数据但没有可推进游标。');
    }
    if (changes.length === 0 && cursor !== null && nextCursor === null) {
      throw new SyncProtocolError('空页不得清空已确认同步游标。');
    }
    if (nextCursor !== null) seenCursors.add(nextCursor);

    if (changes.length > 0 || nextCursor !== cursor) {
      await database.withExclusiveTransactionAsync(async (transaction) => {
        for (const change of changes) await saveCachedEntity(transaction, scope.userId, change, now());
        await saveSyncCursor(transaction, scope, nextCursor, now());
      });
    }

    if (!hasMore) return;
    cursor = nextCursor;
  }
}

export async function pushOperations(
  userId: string,
  scope: LocalSyncScope,
  database: SQLite.SQLiteDatabase,
  api: MobileSyncApiClient,
  deviceId: string,
  now: () => string,
): Promise<void> {
  if (scope.userId !== userId) throw new SyncProtocolError('同步范围与当前用户不一致。');
  const recoveryTime = plusSeconds(now(), RETRY_AFTER_SECONDS);
  await recoverSendingOperations(database, userId, recoveryTime, now());

  const pending = (await listPendingOperations(database, userId))
    .filter((operation) => operation.state === 'PENDING' && isRetryDue(operation, now))
    .sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.operationId.localeCompare(right.operationId));

  for (let offset = 0; offset < pending.length; offset += MAX_BATCH_SIZE) {
    const batch = pending.slice(offset, offset + MAX_BATCH_SIZE);
    await database.withExclusiveTransactionAsync(async (transaction) => {
      for (const operation of batch) await markPendingSending(transaction, userId, operation.operationId, now());
    });

    let response: Awaited<ReturnType<MobileSyncApiClient['applySyncOperations']>>;
    try {
      response = await api.applySyncOperations({ deviceId, operations: batch.map(toWireOperation) });
    } catch {
      await retryBatch(database, userId, batch, plusSeconds(now(), RETRY_AFTER_SECONDS), now());
      return;
    }

    try {
      const results = validateOperationResults(response, batch);
      await database.withExclusiveTransactionAsync(async (transaction) => {
        for (const operation of batch) {
          const result = results.get(operation.operationId);
          if (result === undefined) throw new SyncProtocolError('同步结果缺少 operationId。');
          await applyOperationResult(transaction, userId, operation, result, now);
        }
      });
    } catch (error) {
      await retryBatch(database, userId, batch, plusSeconds(now(), RETRY_AFTER_SECONDS), now());
      if (error instanceof SyncProtocolError) throw error;
      throw new SyncProtocolError('同步结果落库失败，操作保留为可重试状态。');
    }
  }
}

type ValidatedChangePage = { changes: SyncChange[]; nextCursor: string | null; hasMore: boolean };

function validateChangePage(value: unknown): ValidatedChangePage {
  // 游标一旦畸形就不能推断或清空本地确认值，避免缓存与服务端游标失去对应关系。
  if (!isRecord(value) || !Array.isArray(value.data) || !isRecord(value.meta)
    || typeof value.meta.hasMore !== 'boolean' || !isNonEmptyString(value.meta.requestId)
    || !('nextCursor' in value.meta)
    || (value.meta.nextCursor !== null && (typeof value.meta.nextCursor !== 'string' || value.meta.nextCursor.length === 0))) {
    throw new SyncProtocolError();
  }
  const changes = value.data.filter(isSyncChange);
  if (changes.length !== value.data.length) throw new SyncProtocolError();
  for (let index = 1; index < changes.length; index += 1) {
    if (changes[index - 1].sequence >= changes[index].sequence) throw new SyncProtocolError('同步变更序列倒退或重复。');
  }
  const nextCursor = value.meta.nextCursor as string | null;
  if (value.meta.hasMore && nextCursor === null) throw new SyncProtocolError('分页响应缺少可推进游标。');
  return { changes, nextCursor, hasMore: value.meta.hasMore };
}

function validateOperationResults(
  value: unknown,
  batch: readonly PendingSyncOperation[],
): Map<string, SyncOperationResult> {
  if (!isRecord(value) || !isRecord(value.data) || !Array.isArray(value.data.results)) throw new SyncProtocolError();
  const results = value.data.results.filter(isSyncOperationResult);
  if (results.length !== value.data.results.length || results.length !== batch.length) throw new SyncProtocolError();
  const expected = new Set(batch.map((operation) => operation.operationId));
  const mapped = new Map<string, SyncOperationResult>();
  for (const result of results) {
    if (mapped.has(result.operationId) || !expected.has(result.operationId)) throw new SyncProtocolError('同步结果 operationId 重复或未知。');
    mapped.set(result.operationId, result);
  }
  if (mapped.size !== expected.size) throw new SyncProtocolError('同步结果 operationId 数量不匹配。');
  return mapped;
}

async function applyOperationResult(
  transaction: SQLite.SQLiteDatabase,
  userId: string,
  operation: PendingSyncOperation,
  result: SyncOperationResult,
  now: () => string,
): Promise<void> {
  switch (result.status) {
    case 'APPLIED':
      await deletePendingOperation(transaction, userId, operation.operationId);
      return;
    case 'DUPLICATE':
      if ('entityId' in result) {
        await deletePendingOperation(transaction, userId, operation.operationId);
      } else if (result.error.code === 'VERSION_CONFLICT') {
        await saveSyncConflictInTransaction(transaction, userId, operation.operationId, result.error, now());
      } else {
        await updatePendingOperationState(transaction, userId, operation.operationId, 'REJECTED', now());
      }
      return;
    case 'CONFLICT':
      await saveSyncConflictInTransaction(transaction, userId, operation.operationId, result.error, now());
      return;
    case 'REJECTED':
      await updatePendingOperationState(transaction, userId, operation.operationId, 'REJECTED', now());
      return;
    case 'RETRYABLE':
      await markPendingRetryable(transaction, userId, operation.operationId, plusSeconds(now(), RETRY_AFTER_SECONDS), now());
      return;
  }
}

async function retryBatch(
  database: SQLite.SQLiteDatabase,
  userId: string,
  batch: readonly PendingSyncOperation[],
  retryAfterAt: string,
  updatedAt: string,
): Promise<void> {
  await database.withExclusiveTransactionAsync(async (transaction) => {
    for (const operation of batch) {
      await markPendingRetryable(transaction, userId, operation.operationId, retryAfterAt, updatedAt);
    }
  });
}

function isRetryDue(operation: PendingSyncOperation, now: () => string): boolean {
  return operation.retryAfterAt === null || operation.retryAfterAt <= now();
}

function toWireOperation(operation: PendingSyncOperation): SyncOperation {
  const common = {
    operationId: operation.operationId,
    idempotencyKey: operation.idempotencyKey,
    entityType: 'TRANSACTION' as const,
    entityId: operation.entityId,
    payloadVersion: 1 as const,
    createdAt: operation.createdAt,
  };
  switch (operation.operationType) {
    case 'CREATE':
      return { ...common, operationType: 'CREATE', baseVersion: null, payload: operation.payload };
    case 'UPDATE':
      return { ...common, operationType: 'UPDATE', baseVersion: operation.baseVersion, payload: operation.payload };
    case 'REVERSE':
      return { ...common, operationType: 'REVERSE', baseVersion: operation.baseVersion, payload: operation.payload };
  }
}

function plusSeconds(value: string, seconds: number): string {
  const time = Date.parse(value);
  if (!Number.isFinite(time)) throw new SyncProtocolError('同步本地时钟无效。');
  return new Date(time + seconds * 1000).toISOString();
}

function isSyncChange(value: unknown): value is SyncChange {
  return isRecord(value)
    && typeof value.sequence === 'number' && Number.isSafeInteger(value.sequence) && value.sequence > 0
    && typeof value.entityType === 'string' && typeof value.entityId === 'string'
    && typeof value.entityVersion === 'number' && Number.isSafeInteger(value.entityVersion) && value.entityVersion > 0
    && (value.changeType === 'UPSERT' || value.changeType === 'TOMBSTONE'
      || value.changeType === 'ACCESS_REVOKED' || value.changeType === 'BOOTSTRAP')
    && value.payloadVersion === 1;
}

function isSyncOperationResult(value: unknown): value is SyncOperationResult {
  if (!isRecord(value) || typeof value.operationId !== 'string' || typeof value.status !== 'string') return false;
  switch (value.status) {
    case 'APPLIED':
    case 'DUPLICATE':
      return ('entityId' in value && typeof value.entityId === 'string'
        && typeof value.entityVersion === 'number' && Number.isSafeInteger(value.entityVersion) && value.entityVersion > 0)
        || (value.status === 'DUPLICATE' && isProblemResult(value) && isFinalDuplicateProblem(value.error));
    case 'CONFLICT':
      return isProblemResult(value) && isVersionConflictProblem(value.error);
    case 'REJECTED':
      return isProblemResult(value) && isRejectedProblem(value.error);
    case 'RETRYABLE':
      return isProblemResult(value) && value.retryAfterSeconds === RETRY_AFTER_SECONDS && isRetryableProblem(value.error);
    default:
      return false;
  }
}

function isProblemResult(value: Record<string, unknown>): value is Record<string, unknown> & { error: SyncProblem } {
  return isProblem(value.error);
}

function isVersionConflictProblem(problem: SyncProblem): boolean {
  if (problem.code !== 'VERSION_CONFLICT' || problem.status !== 409 || !isRecord(problem.versionConflict)) return false;
  return isVersionConflictDetails(problem.versionConflict);
}

function isRejectedProblem(problem: SyncProblem): boolean {
  return problem.code !== 'VERSION_CONFLICT' && problem.versionConflict === undefined;
}

function isRetryableProblem(problem: SyncProblem): boolean {
  return problem.versionConflict === undefined && ((problem.code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS' && problem.status === 409)
    || (problem.code === 'INTERNAL_ERROR' && problem.status === 500));
}

function isFinalDuplicateProblem(problem: SyncProblem): boolean {
  return isVersionConflictProblem(problem) || isRejectedProblem(problem);
}

function isProblem(value: unknown): value is SyncProblem {
  // SQLite 行和 HTTP 响应都是动态数据，必须先恢复生成契约的不变量才允许写入终态。
  if (!isRecord(value)
    || !hasOnlyKeys(value, ['type', 'title', 'status', 'code', 'detail', 'instance', 'requestId', 'versionConflict', 'fieldErrors'])
    || !isNonEmptyString(value.type)
    || !isNonEmptyString(value.title)
    || typeof value.status !== 'number' || !Number.isFinite(value.status) || !Number.isSafeInteger(value.status) || value.status < 400 || value.status > 599
    || !isNonEmptyString(value.code)
    || !isNonEmptyString(value.requestId)
    || !isNullableString(value.detail)
    || !isNullableString(value.instance)) return false;
  if (value.fieldErrors !== undefined && (!Array.isArray(value.fieldErrors) || value.fieldErrors.some((fieldError) => !isFieldError(fieldError)))) return false;
  if (value.versionConflict !== undefined && (!isRecord(value.versionConflict) || !isVersionConflictDetails(value.versionConflict))) return false;
  return true;
}

function isFieldError(value: unknown): boolean {
  return isRecord(value)
    && hasOnlyKeys(value, ['field', 'code', 'message'])
    && isNonEmptyString(value.field)
    && isNonEmptyString(value.code)
    && isNullableString(value.message);
}

function hasOnlyKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const allowed = new Set(keys);
  return Object.keys(value).every((key) => allowed.has(key));
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isNullableString(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || typeof value === 'string';
}

function isVersionConflictDetails(value: Record<string, unknown>): boolean {
  if (!hasOnlyKeys(value, ['currentVersion', 'currentEtag', 'resourceLocation'])) return false;
  const { currentVersion, currentEtag, resourceLocation } = value;
  return typeof currentVersion === 'number' && Number.isSafeInteger(currentVersion) && currentVersion > 0
    && typeof currentEtag === 'string' && currentEtag === `"${currentVersion}"`
    && typeof resourceLocation === 'string' && /^\/(?!\/)[^\s]*$/.test(resourceLocation);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
