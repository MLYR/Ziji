import { useEffect, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';

import { ApiClientError } from '@/api/api-client';
import {
  mobileAuthenticationSession,
  mobileDeviceIdentity,
  mobileSyncApiClient,
  mobileTransactionApiClient,
} from '@/auth/default-auth-session';
import {
  createRevisionOperation,
  createSyncConflictResolutionPort,
} from '@/sync/conflict-resolution';
import { parseTransactionResourceLocation } from '@/sync/conflict-resource';
import { synchronize } from '@/sync/sync-coordinator';
import {
  getLocalDatabase,
  getSyncConflict,
  listPendingOperations,
  type PendingSyncOperation,
  type SyncConflict,
} from '@/storage/local-database';
import type * as SQLite from 'expo-sqlite';

type CloudSummary = { operationId: string; businessDate: string; status: string; type: string; version: number };

interface SyncItem {
  operation: PendingSyncOperation;
  conflict: SyncConflict | null;
}

export function mapSyncStatus(operation: PendingSyncOperation): string {
  if (operation.state === 'SENDING') return '同步中';
  if (operation.state === 'CONFLICT') return '需要处理冲突';
  if (operation.state === 'REJECTED') return '服务端拒绝';
  if (operation.retryAfterAt !== null) return '等待重试';
  return '待同步';
}

function localSemanticType(operation: PendingSyncOperation): string {
  return operation.operationType === 'UPDATE' ? operation.payload.replacement.type : '作废';
}

function localBusinessDate(operation: PendingSyncOperation): string | null {
  return operation.operationType === 'UPDATE' ? operation.payload.replacement.businessDate : null;
}

function isAuthenticationError(error: unknown, status: 401 | 403): error is ApiClientError {
  return error instanceof ApiClientError && error.problem.status === status;
}

export function SyncStatusPanel({ userId }: { userId: string }) {
  const [database, setDatabase] = useState<SQLite.SQLiteDatabase | null>(null);
  const [items, setItems] = useState<SyncItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isOffline, setIsOffline] = useState(false);
  const [resolvingOperationId, setResolvingOperationId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [cloud, setCloud] = useState<CloudSummary | null>(null);
  const [revisionReasons, setRevisionReasons] = useState<Record<string, string>>({});

  async function load(databaseOverride?: SQLite.SQLiteDatabase): Promise<void> {
    const currentDatabase = databaseOverride ?? database ?? await getLocalDatabase();
    const operations = await listPendingOperations(currentDatabase, userId);
    const conflicts = await Promise.all(operations.map(async (operation) => ({
      operation,
      conflict: operation.state === 'CONFLICT' ? await getSyncConflict(currentDatabase, userId, operation.operationId) : null,
    })));
    setDatabase(currentDatabase);
    setItems(conflicts);
  }

  useEffect(() => {
    let active = true;
    setIsLoading(true);
    setMessage(null);
    setIsOffline(false);
    setCloud(null);
    void getLocalDatabase().then(async (currentDatabase) => {
      if (!active) return;
      await load(currentDatabase);
    }).catch(() => {
      if (active) setMessage('无法读取本机同步状态，请稍后重试。');
    }).finally(() => {
      if (active) setIsLoading(false);
    });
    return () => { active = false; };
  }, [userId]);

  async function synchronizeOnce(): Promise<void> {
    if (!database) return;
    const { deviceId } = await mobileDeviceIdentity.get();
    await synchronize(userId, { database, api: mobileSyncApiClient, deviceId });
  }

  async function invalidateAuthentication(): Promise<void> {
    await mobileAuthenticationSession.invalidateAuthentication();
  }

  async function runSync(): Promise<void> {
    if (!database || isSyncing) return;
    setIsSyncing(true);
    setMessage(null);
    setIsOffline(false);
    try {
      await synchronizeOnce();
      await load(database);
      setMessage('同步检查完成。');
    } catch (error) {
      if (isAuthenticationError(error, 401)) {
        const refreshed = await mobileAuthenticationSession.refresh();
        if (refreshed.status === 'AUTHENTICATED' && refreshed.userId === userId) {
          try {
            await synchronizeOnce();
            await load(database);
            setMessage('登录已恢复，同步检查完成。');
            return;
          } catch (retryError) {
            // 认证恢复后仍失败时落入可恢复提示，不把本地队列误删。
            if (isAuthenticationError(retryError, 401) || isAuthenticationError(retryError, 403)) {
              await invalidateAuthentication();
              return;
            }
            setMessage('同步暂时不可用，本地操作仍保留。');
            return;
          }
        }
        setMessage('登录状态需要恢复，请重新登录后再同步。');
        return;
      }
      if (isAuthenticationError(error, 403)) {
        // 403 表示当前主体不再可用，必须立即关闭 userId SQLite scope，不能继续显示或读取队列。
        await invalidateAuthentication();
        return;
      }
      setIsOffline(true);
      setMessage('当前无法连接服务，操作已保留在本机，恢复网络后可重试。');
      await load(database).catch(() => undefined);
    } finally {
      setIsSyncing(false);
    }
  }

  async function discardLocal(operationId: string): Promise<void> {
    if (!database || resolvingOperationId !== null) return;
    setResolvingOperationId(operationId);
    try {
      await createSyncConflictResolutionPort(database).discardLocal(userId, operationId);
      await load(database);
      setMessage('已接受云端版本并移除本地冲突。');
    } catch {
      setMessage('冲突处理失败，本地操作仍保留。');
    } finally {
      setResolvingOperationId(null);
    }
  }

  async function retryRevision(item: SyncItem): Promise<void> {
    if (!database || resolvingOperationId !== null) return;
    try {
      const reason = revisionReasons[item.operation.operationId] ?? '';
      if (reason.trim().length === 0) {
        setMessage('请填写修订或作废原因后再重试。');
        return;
      }
      setResolvingOperationId(item.operation.operationId);
      const revised = createRevisionOperation(item.operation, reason);
      await createSyncConflictResolutionPort(database).retryWithRevision(userId, item.operation.operationId, revised);
      await load(database);
      setMessage('修订已加入待同步队列。');
    } catch {
      setMessage('修订重试失败，本地冲突仍保留。');
    } finally {
      setResolvingOperationId(null);
    }
  }

  async function viewCloud(item: SyncItem): Promise<void> {
    const transactionId = parseTransactionResourceLocation(item.conflict?.problem.versionConflict?.resourceLocation);
    if (!transactionId) {
      setMessage('服务端冲突定位无效，已拒绝请求。');
      return;
    }
    try {
      const response = await mobileTransactionApiClient.getTransaction(transactionId);
      setCloud({ operationId: item.operation.operationId, type: response.data.type, businessDate: response.data.businessDate, status: response.data.status, version: response.data.version });
      setMessage('已读取云端交易摘要。');
    } catch (error) {
      if (isAuthenticationError(error, 401)) {
        const refreshed = await mobileAuthenticationSession.refresh();
        if (refreshed.status === 'AUTHENTICATED' && refreshed.userId === userId) {
          try {
            const response = await mobileTransactionApiClient.getTransaction(transactionId);
            setCloud({ operationId: item.operation.operationId, type: response.data.type, businessDate: response.data.businessDate, status: response.data.status, version: response.data.version });
            setMessage('登录已恢复，已读取云端交易摘要。');
            return;
          } catch (retryError) {
            if (isAuthenticationError(retryError, 401) || isAuthenticationError(retryError, 403)) {
              await invalidateAuthentication();
              return;
            }
            setMessage('暂时无法读取云端交易，请稍后重试。');
            return;
          }
        }
        setMessage('登录状态需要恢复，请重新登录后再查看云端交易。');
        return;
      }
      if (isAuthenticationError(error, 403)) {
        // 冲突详情同样受当前主体约束；禁止在无权后保留 userId SQLite scope。
        await invalidateAuthentication();
        return;
      }
      setMessage('暂时无法读取云端交易，请稍后重试。');
    }
  }

  if (isLoading) {
    return <View className="mt-6 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" accessibilityLiveRegion="polite"><Text className="text-base text-ink-light dark:text-ink-dark">正在读取同步状态…</Text></View>;
  }

  return (
    <View className="mt-6 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" accessibilityLiveRegion="polite">
      <View className="flex-row items-center justify-between">
        <Text className="text-xl font-bold text-ink-light dark:text-ink-dark">同步状态</Text>
        <Pressable
          accessibilityLabel={isSyncing ? '正在同步' : '立即同步'}
          accessibilityRole="button"
          accessibilityState={{ busy: isSyncing, disabled: isSyncing }}
          className={`min-h-11 min-w-11 items-center justify-center rounded-lg border border-accent px-3 ${isSyncing ? 'opacity-50' : 'active:opacity-70'}`}
          disabled={isSyncing}
          onPress={() => void runSync()}
        >
          <Text className="font-semibold text-ink-light dark:text-ink-dark">{isSyncing ? '同步中…' : '同步'}</Text>
        </Pressable>
      </View>

      {message ? <Text className="mt-3 text-sm leading-5 text-ink-light dark:text-ink-dark" accessibilityRole="alert">{message}</Text> : null}
      {isOffline ? <Text className="mt-3 text-sm leading-5 text-ink-light dark:text-ink-dark">离线：操作已保存在本机，网络恢复后可重试。</Text> : null}
      {items.length === 0 ? <Text className="mt-4 text-base text-muted-light dark:text-muted-dark">当前没有待处理的同步操作。</Text> : null}

      {items.map((item) => {
        const { operation } = item;
        const canRevise = operation.state === 'CONFLICT' && (operation.operationType === 'UPDATE' || operation.operationType === 'REVERSE');
        const isResolving = resolvingOperationId === operation.operationId;
        const cloudSummary = cloud?.operationId === operation.operationId ? cloud : null;
        return (
          <View key={operation.operationId} className="mt-4 rounded-lg border border-muted-light p-4 dark:border-muted-dark">
            <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{operation.operationType}：{mapSyncStatus(operation)}</Text>
            {operation.state === 'REJECTED' ? <Text className="mt-2 text-sm text-ink-light dark:text-ink-dark">服务端已拒绝，不会自动重试；请检查输入后重新创建操作。</Text> : null}
            {operation.state === 'CONFLICT' ? (
              <>
                <View className="mt-3 flex-row gap-3">
                  <View className="flex-1 rounded-lg bg-canvas-light p-3 dark:bg-canvas-dark">
                    <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">本地修改</Text>
                    <Text className="mt-1 text-sm leading-5 text-muted-light dark:text-muted-dark">操作：{operation.operationType}</Text>
                    <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">语义：{localSemanticType(operation)}</Text>
                    {localBusinessDate(operation) ? <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">业务日期：{localBusinessDate(operation)}</Text> : null}
                    <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">状态：{mapSyncStatus(operation)}</Text>
                  </View>
                  <View className="flex-1 rounded-lg bg-canvas-light p-3 dark:bg-canvas-dark">
                    <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">云端交易</Text>
                    {cloudSummary ? (
                      <>
                        <Text className="mt-1 text-sm leading-5 text-muted-light dark:text-muted-dark">类型：{cloudSummary.type}</Text>
                        <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">业务日期：{cloudSummary.businessDate}</Text>
                        <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">状态：{cloudSummary.status}</Text>
                        <Text className="text-sm leading-5 text-muted-light dark:text-muted-dark">版本：{cloudSummary.version}</Text>
                      </>
                    ) : <Text className="mt-1 text-sm leading-5 text-muted-light dark:text-muted-dark">查看后显示安全摘要。</Text>}
                  </View>
                </View>
                <Pressable accessibilityLabel="查看云端交易" accessibilityRole="button" className="mt-3 min-h-11 justify-center rounded-lg border border-accent px-3 active:opacity-70" onPress={() => void viewCloud(item)}>
                  <Text className="font-semibold text-ink-light dark:text-ink-dark">查看云端交易</Text>
                </Pressable>
                <Pressable accessibilityLabel={isResolving ? '正在处理本地冲突' : '接受云端并放弃本地'} accessibilityRole="button" accessibilityState={{ busy: isResolving, disabled: isResolving }} className={`mt-3 min-h-11 justify-center rounded-lg border border-accent px-3 ${isResolving ? 'opacity-50' : 'active:opacity-70'}`} disabled={isResolving} onPress={() => void discardLocal(operation.operationId)}>
                  <Text className="font-semibold text-ink-light dark:text-ink-dark">{isResolving ? '处理中…' : '接受云端并放弃本地'}</Text>
                </Pressable>
                <Text className="mt-2 text-sm leading-5 text-muted-light dark:text-muted-dark">接受云端并放弃本地只删除本机 pending 与 conflict，不写服务端。</Text>
                {canRevise ? (
                  <>
                    <TextInput
                      accessibilityLabel="修订或作废原因"
                      className="mt-3 min-h-11 rounded-lg bg-canvas-light px-3 text-base text-ink-light dark:bg-canvas-dark dark:text-ink-dark"
                      editable={!isResolving}
                      onChangeText={(value) => setRevisionReasons((current) => ({ ...current, [operation.operationId]: value }))}
                      placeholder="修订或作废原因"
                      value={revisionReasons[operation.operationId] ?? ''}
                    />
                    <Pressable accessibilityLabel={isResolving ? '正在处理本地冲突' : '修订后重试'} accessibilityRole="button" accessibilityState={{ busy: isResolving, disabled: isResolving }} className={`mt-3 min-h-11 justify-center rounded-lg bg-accent px-3 ${isResolving ? 'opacity-50' : 'active:opacity-70'}`} disabled={isResolving} onPress={() => void retryRevision(item)}>
                      <Text className="font-semibold text-canvas-dark">{isResolving ? '处理中…' : '修订后重试'}</Text>
                    </Pressable>
                    <Text className="mt-2 text-sm leading-5 text-muted-light dark:text-muted-dark">按本地修改重试会沿用本地语义载荷，以云端当前版本和新三元组重新入队。</Text>
                  </>
                ) : null}
              </>
            ) : null}
          </View>
        );
      })}
    </View>
  );
}
