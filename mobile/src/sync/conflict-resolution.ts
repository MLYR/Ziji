import type * as SQLite from 'expo-sqlite';

import type { components } from '@ziji/api-types';

import {
  deletePendingOperation,
  enqueuePendingOperation,
  getPendingOperation,
  getSyncConflict,
  type PendingSyncOperation,
} from '@/storage/local-database';

type SyncOperation = components['schemas']['SyncOperation'];

export interface SyncConflictResolutionPort {
  discardLocal(userId: string, operationId: string): Promise<void>;
  retryWithRevision(userId: string, oldOperationId: string, revisedOperation: SyncOperation): Promise<void>;
}

export function createRevisionOperation(operation: PendingSyncOperation, reason: string): SyncOperation {
  const normalizedReason = reason.trim();
  if (normalizedReason.length === 0) throw new Error('请填写修订或作废原因。');
  if (operation.operationType !== 'UPDATE' && operation.operationType !== 'REVERSE') {
    throw new Error('该冲突操作不能在本机修订。');
  }

  const operationId = globalThis.crypto?.randomUUID?.();
  const idempotencyNonce = globalThis.crypto?.randomUUID?.();
  if (!operationId || !idempotencyNonce) throw new Error('设备无法安全生成新的同步操作标识。');

  // 服务端按新 operation/baseVersion/类型化 payload 计算规范化 Hash；客户端只生成不可复用的新三元组。
  const common = {
    ...operation,
    operationId,
    idempotencyKey: `sync-${idempotencyNonce}`,
    createdAt: new Date().toISOString(),
  };
  return operation.operationType === 'UPDATE'
    ? { ...common, operationType: 'UPDATE', payload: { ...operation.payload, reason: normalizedReason } }
    : { ...common, operationType: 'REVERSE', payload: { ...operation.payload, reason: normalizedReason } };
}

export function createSyncConflictResolutionPort(database: SQLite.SQLiteDatabase): SyncConflictResolutionPort {
  return {
    discardLocal(userId, operationId) {
      return database.withExclusiveTransactionAsync(async (transaction) => {
        const pending = await getPendingOperation(transaction, userId, operationId);
        const conflict = await getSyncConflict(transaction, userId, operationId);
        if (!pending || pending.state !== 'CONFLICT' || !conflict) {
          throw new Error('本地冲突不存在或不属于当前用户。');
        }

        // 外键要求先删冲突副本再删旧操作；两步必须共用一个事务，失败时完整回滚。
        const result = await transaction.runAsync(
          'DELETE FROM sync_conflicts WHERE user_id = ? AND operation_id = ?;',
          [userId, operationId],
        );
        if (result.changes !== 1) throw new Error('本地冲突删除失败。');
        await deletePendingOperation(transaction, userId, operationId);
      });
    },

    retryWithRevision(userId, oldOperationId, revisedOperation) {
      return database.withExclusiveTransactionAsync(async (transaction) => {
        const pending = await getPendingOperation(transaction, userId, oldOperationId);
        const conflict = await getSyncConflict(transaction, userId, oldOperationId);
        const currentVersion = conflict?.problem.versionConflict?.currentVersion;
        if (!pending || pending.state !== 'CONFLICT' || !conflict || typeof currentVersion !== 'number'
          || !Number.isSafeInteger(currentVersion) || currentVersion < 1) {
          throw new Error('本地冲突不存在、已拒绝或缺少当前版本。');
        }
        if (revisedOperation.operationType !== 'UPDATE' && revisedOperation.operationType !== 'REVERSE') {
          throw new Error('冲突修订只允许 UPDATE 或 REVERSE。');
        }
        if (revisedOperation.operationType !== pending.operationType) {
          // 修订与作废具有不同账务语义，冲突重试只能保留原操作类型，不能借此切换命令。
          throw new Error('修订操作必须保持原 UPDATE 或 REVERSE 类型。');
        }
        if (revisedOperation.entityType !== 'TRANSACTION' || revisedOperation.entityId !== pending.entityId) {
          throw new Error('修订操作必须指向原冲突交易。');
        }
        if (revisedOperation.operationId === oldOperationId || revisedOperation.idempotencyKey === pending.idempotencyKey) {
          throw new Error('修订操作必须使用新的 operationId 与 Idempotency-Key。');
        }

        const nextOperation: SyncOperation = { ...revisedOperation, baseVersion: currentVersion };

        // 先入队新三元组再删除旧记录，仍在同一事务内；任一写入失败都不会留下半个修订。
        await enqueuePendingOperation(transaction, userId, nextOperation, revisedOperation.createdAt);
        const conflictDelete = await transaction.runAsync(
          'DELETE FROM sync_conflicts WHERE user_id = ? AND operation_id = ?;',
          [userId, oldOperationId],
        );
        if (conflictDelete.changes !== 1) throw new Error('旧冲突删除失败。');
        await deletePendingOperation(transaction, userId, oldOperationId);
      });
    },
  };
}
