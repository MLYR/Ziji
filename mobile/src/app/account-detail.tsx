import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAccountsApiClient } from '@/auth/default-auth-session';
import type { Account, AccountBalance } from '@/api/api-client';

function accountEtag(version: number): string {
  return `"${version}"`;
}

/** 账户详情路由：余额、编辑（If-Match）与归档确认（原因 + 非零余额显式确认）。 */
export default function AccountDetailRoute() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const accountId = typeof params.id === 'string' ? params.id : null;

  const [account, setAccount] = useState<Account | null>(null);
  const [balance, setBalance] = useState<AccountBalance | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const [institution, setInstitution] = useState('');
  const [archiving, setArchiving] = useState(false);
  const [reason, setReason] = useState('');
  const [confirmNonZero, setConfirmNonZero] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const archiveKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!accountId) {
      setMessage('缺少账户 ID。');
      return;
    }
    let cancelled = false;
    mobileAccountsApiClient.getAccount(accountId)
      .then((envelope) => {
        if (cancelled) return;
        setAccount(envelope.data);
        setName(envelope.data.name);
        setInstitution(envelope.data.institution ?? '');
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载账户：可能不存在或不可见。');
      });
    mobileAccountsApiClient.getAccountBalance(accountId)
      .then((envelope) => {
        if (!cancelled) setBalance(envelope.data);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  function reload() {
    if (!accountId) return;
    mobileAccountsApiClient.getAccount(accountId)
      .then((envelope) => setAccount(envelope.data))
      .catch(() => undefined);
  }

  async function saveEdit() {
    if (!accountId || !account) return;
    setSubmitting(true);
    setMessage(null);
    try {
      const envelope = await mobileAccountsApiClient.updateAccount(accountId, accountEtag(account.version), {
        name: name.trim(),
        institution: institution.trim() === '' ? null : institution.trim(),
      });
      setAccount(envelope.data);
      setEditing(false);
    } catch (error) {
      const problem = (error as { problem?: { code?: string; detail?: string } }).problem;
      setMessage(problem?.code === 'VERSION_CONFLICT'
        ? '账户已被其他设备修改，请刷新后重试。'
        : problem?.detail ?? '保存失败，请稍后重试。');
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmArchive() {
    if (!accountId || !account) return;
    setSubmitting(true);
    setMessage(null);
    try {
      if (!archiveKeyRef.current) archiveKeyRef.current = globalThis.crypto.randomUUID();
      const envelope = await mobileAccountsApiClient.archiveAccount(
        accountId,
        accountEtag(account.version),
        archiveKeyRef.current,
        { reason: reason.trim(), confirmNonZeroBalance: confirmNonZero },
      );
      setAccount(envelope.data);
      setArchiving(false);
      router.replace('/accounts');
    } catch (error) {
      const problem = (error as { problem?: { code?: string; detail?: string } }).problem;
      setMessage(problem?.code === 'NON_ZERO_BALANCE_CONFIRMATION_REQUIRED'
        ? '账户余额非零，需要勾选确认后才能归档。'
        : problem?.code === 'VERSION_CONFLICT'
          ? '账户已被其他设备修改，请刷新后重试。'
          : problem?.detail ?? '归档失败，请稍后重试。');
    } finally {
      setSubmitting(false);
    }
  }

  if (message && !account) {
    return (
      <SafeAreaView style={{ flex: 1 }} edges={['top']}>
        <View className="p-4">
          <Text accessibilityRole="alert" testID="account-detail-error" className="text-sm text-destructive">{message}</Text>
        </View>
      </SafeAreaView>
    );
  }

  const hasNonZeroBalance = balance ? Number(balance.ledgerBalance) !== 0 : false;

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="返回账户列表" onPress={() => router.back()} testID="account-detail-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>

        {account ? (
          <>
            <View className="flex-row items-center gap-2">
              <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">{account.name}</Text>
              {account.status === 'ARCHIVED' ? <Text className="text-xs text-muted-light dark:text-muted-dark">已归档</Text> : null}
            </View>

            {balance ? (
              <View className="gap-1 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
                <Text className="text-lg font-bold text-ink-light dark:text-ink-dark">{balance.ledgerBalance} {balance.currency}</Text>
                <Text className="text-xs text-muted-light dark:text-muted-dark">可用 {balance.availableBalance} · 不可用 {balance.unavailableAmount}</Text>
                {balance.liquidityStatus === 'NEGATIVE_AVAILABLE' ? (
                  <Text accessibilityRole="alert" className="text-xs text-amber-600 dark:text-amber-400">可用余额为负：透支或冻结超过账面余额</Text>
                ) : null}
              </View>
            ) : null}

            {account.status === 'ACTIVE' && !editing ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="编辑账户"
                onPress={() => setEditing(true)}
                testID="account-edit-open"
                className="min-h-11 items-center justify-center rounded-lg border border-accent active:opacity-70"
              >
                <Text className="font-semibold text-ink-light dark:text-ink-dark">编辑账户</Text>
              </Pressable>
            ) : null}

            {editing ? (
              <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
                <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">名称</Text>
                <TextInput value={name} onChangeText={setName} testID="account-edit-name"
                  className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">机构（可选）</Text>
                <TextInput value={institution} onChangeText={setInstitution} testID="account-edit-institution"
                  className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="保存修改"
                  disabled={submitting}
                  onPress={() => void saveEdit()}
                  testID="account-edit-save"
                  className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : ''}`}
                >
                  <Text className="font-bold text-canvas-dark">保存</Text>
                </Pressable>
                <Pressable accessibilityRole="button" onPress={() => setEditing(false)} testID="account-edit-cancel"
                  className="min-h-11 items-center justify-center rounded-lg border border-accent/40">
                  <Text className="text-ink-light dark:text-ink-dark">取消</Text>
                </Pressable>
              </View>
            ) : null}

            {account.status === 'ACTIVE' && !archiving ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="归档账户"
                onPress={() => setArchiving(true)}
                testID="account-archive-open"
                className="min-h-11 items-center justify-center rounded-lg border border-accent/40 active:opacity-70"
              >
                <Text className="text-ink-light dark:text-ink-dark">归档账户</Text>
              </Pressable>
            ) : null}

            {archiving ? (
              <View className="gap-2 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
                <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">归档原因</Text>
                <TextInput value={reason} onChangeText={setReason} testID="account-archive-reason"
                  className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
                {hasNonZeroBalance ? (
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: confirmNonZero }}
                    onPress={() => setConfirmNonZero((value) => !value)}
                    testID="account-archive-confirm"
                    className="flex-row items-center gap-2"
                  >
                    <View className={`size-5 items-center justify-center rounded border ${confirmNonZero ? 'border-accent bg-accent' : 'border-accent/40'}`}>
                      {confirmNonZero ? <Text className="text-canvas-dark">✓</Text> : null}
                    </View>
                    <Text className="flex-1 text-sm text-ink-light dark:text-ink-dark">账户余额非零，我确认仍要归档</Text>
                  </Pressable>
                ) : null}
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="确认归档"
                  accessibilityState={{ disabled: submitting || (hasNonZeroBalance && !confirmNonZero) }}
                  disabled={submitting || (hasNonZeroBalance && !confirmNonZero)}
                  onPress={() => void confirmArchive()}
                  testID="account-archive-submit"
                  className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting || (hasNonZeroBalance && !confirmNonZero) ? 'opacity-50' : ''}`}
                >
                  <Text className="font-bold text-canvas-dark">确认归档</Text>
                </Pressable>
                <Pressable accessibilityRole="button" onPress={() => setArchiving(false)} testID="account-archive-cancel"
                  className="min-h-11 items-center justify-center rounded-lg border border-accent/40">
                  <Text className="text-ink-light dark:text-ink-dark">取消</Text>
                </Pressable>
              </View>
            ) : null}

            {message ? (
              <Text accessibilityRole="alert" testID="account-detail-message" className="text-sm text-destructive">{message}</Text>
            ) : null}
          </>
        ) : (
          <Text className="text-base text-muted-light dark:text-muted-dark">正在加载账户…</Text>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
