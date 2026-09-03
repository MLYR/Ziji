import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAccountsApiClient } from '@/auth/default-auth-session';
import type { Account, AccountBalance } from '@/api/api-client';

const CLASS_LABELS: Record<string, string> = { ASSET: '资产', INVESTMENT: '投资', LIABILITY: '负债' };
const TYPE_LABELS: Record<string, string> = {
  BANK: '银行', WECHAT: '微信', ALIPAY: '支付宝', CASH: '现金', BROKERAGE: '券商', FUND: '基金',
  CREDIT_CARD: '信用卡', LOAN: '贷款', CONSUMER_LOAN: '消费贷款', OTHER: '其他',
};

interface AccountRowProps {
  account: Account;
  onOpen: (accountId: string) => void;
}

function AccountRow({ account, onOpen }: AccountRowProps) {
  const [balance, setBalance] = useState<AccountBalance | null>(null);
  const [balanceFailed, setBalanceFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (account.status !== 'ACTIVE') return;
    mobileAccountsApiClient.getAccountBalance(account.id)
      .then((envelope) => {
        if (!cancelled) setBalance(envelope.data);
      })
      .catch(() => {
        if (!cancelled) setBalanceFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [account.id, account.status]);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`打开账户 ${account.name}`}
      onPress={() => onOpen(account.id)}
      testID={`account-row-${account.id}`}
      className="rounded-xl bg-surface-light p-4 dark:bg-surface-dark"
    >
      <View className="flex-row items-center justify-between">
        <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{account.name}</Text>
        {account.status === 'ARCHIVED' ? <Text className="text-xs text-muted-light dark:text-muted-dark">已归档</Text> : null}
      </View>
      <Text className="mt-1 text-xs text-muted-light dark:text-muted-dark">
        {CLASS_LABELS[account.accountClass] ?? account.accountClass} · {TYPE_LABELS[account.accountType] ?? account.accountType} · {account.currency}
      </Text>
      {account.status === 'ACTIVE' ? (
        balance ? (
          <View className="mt-2">
            <Text className="text-lg font-bold text-ink-light dark:text-ink-dark">
              {balance.ledgerBalance} {balance.currency}
            </Text>
            <Text className="text-xs text-muted-light dark:text-muted-dark">可用 {balance.availableBalance}</Text>
            {balance.liquidityStatus === 'NEGATIVE_AVAILABLE' ? (
              <Text accessibilityRole="alert" className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                可用余额为负：透支或冻结超过账面余额
              </Text>
            ) : null}
          </View>
        ) : (
          <Text className="mt-2 text-xs text-muted-light dark:text-muted-dark">
            {balanceFailed ? '余额不可用' : '余额加载中…'}
          </Text>
        )
      ) : null}
    </Pressable>
  );
}

/** 账户列表路由：三类分组 + 余额状态 + 空态；账户详情经 /account-detail/{id} 打开。 */
export default function AccountsRoute() {
  const router = useRouter();
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    mobileAccountsApiClient.listAccounts(100)
      .then((envelope) => {
        if (!cancelled) setAccounts(envelope.data);
      })
      .catch(() => {
        if (!cancelled) setMessage('无法加载账户：网络或服务暂不可用。');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const groups: { key: string; label: string }[] = [
    { key: 'ASSET', label: '资产账户' },
    { key: 'INVESTMENT', label: '投资账户' },
    { key: 'LIABILITY', label: '负债账户' },
  ];

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="返回首页" onPress={() => router.back()} testID="accounts-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <View className="flex-row items-center justify-between">
          <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">账户</Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="分类与标签"
            onPress={() => router.push('/categories')}
            testID="accounts-open-categories"
            className="mr-2 min-h-11 items-center justify-center rounded-lg border border-accent px-4"
          >
            <Text className="font-semibold text-ink-light dark:text-ink-dark">分类</Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="创建账户"
            onPress={() => router.push('/account-create')}
            testID="accounts-open-create"
            className="min-h-11 items-center justify-center rounded-lg border border-accent px-4"
          >
            <Text className="font-semibold text-ink-light dark:text-ink-dark">创建</Text>
          </Pressable>
        </View>

        {message ? (
          <Text accessibilityRole="alert" testID="accounts-error" className="text-sm text-destructive">{message}</Text>
        ) : null}

        {accounts !== null && accounts.length === 0 ? (
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push('/account-create')}
            testID="accounts-empty"
            className="min-h-11 items-center justify-center rounded-lg bg-accent"
          >
            <Text className="font-bold text-canvas-dark">创建第一个账户</Text>
          </Pressable>
        ) : null}

        {groups.map((group) => {
          const groupAccounts = (accounts ?? []).filter((account) => account.accountClass === group.key);
          if (groupAccounts.length === 0) return null;
          return (
            <View key={group.key} className="gap-2">
              <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">{group.label}</Text>
              {groupAccounts.map((account) => (
                <AccountRow
                  key={account.id}
                  account={account}
                  onOpen={(id) => router.push({ pathname: '/account-detail', params: { id } })}
                />
              ))}
            </View>
          );
        })}
      </ScrollView>
    </SafeAreaView>
  );
}
