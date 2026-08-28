import { useRouter } from 'expo-router';
import { useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAccountsApiClient } from '@/auth/default-auth-session';
import type { CreateAccountRequest } from '@/api/api-client';

const TYPE_MATRIX: Record<'ASSET' | 'INVESTMENT' | 'LIABILITY', { value: AccountTypeValue; label: string }[]> = {
  ASSET: [
    { value: 'BANK', label: '银行' },
    { value: 'WECHAT', label: '微信' },
    { value: 'ALIPAY', label: '支付宝' },
    { value: 'CASH', label: '现金' },
    { value: 'OTHER', label: '其他' },
  ],
  INVESTMENT: [
    { value: 'BROKERAGE', label: '券商' },
    { value: 'FUND', label: '基金' },
    { value: 'OTHER', label: '其他' },
  ],
  LIABILITY: [
    { value: 'CREDIT_CARD', label: '信用卡' },
    { value: 'LOAN', label: '贷款' },
    { value: 'CONSUMER_LOAN', label: '消费贷款' },
    { value: 'OTHER', label: '其他' },
  ],
};

type AccountTypeValue = 'BANK' | 'WECHAT' | 'ALIPAY' | 'CASH' | 'BROKERAGE' | 'FUND' | 'CREDIT_CARD' | 'LOAN' | 'CONSUMER_LOAN' | 'OTHER';

const CURRENCIES = ['CNY', 'USD', 'HKD', 'JPY', 'EUR'] as const;

function currencyMinorUnits(currency: string): number {
  return currency === 'JPY' ? 0 : 2;
}

/** 账户创建路由：大类→子类型矩阵、可选期初余额（原子入账），幂等键防重复创建。 */
export default function AccountCreateRoute() {
  const router = useRouter();
  const [accountClass, setAccountClass] = useState<'ASSET' | 'INVESTMENT' | 'LIABILITY'>('ASSET');
  const [accountType, setAccountType] = useState<'BANK' | 'WECHAT' | 'ALIPAY' | 'CASH' | 'BROKERAGE' | 'FUND' | 'CREDIT_CARD' | 'LOAN' | 'CONSUMER_LOAN' | 'OTHER'>('BANK');
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState<(typeof CURRENCIES)[number]>('CNY');
  const [openingAmount, setOpeningAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const idempotencyKeyRef = useRef(globalThis.crypto.randomUUID());

  const openingBusinessAt = useMemo(() => new Date().toISOString(), []);

  async function submit() {
    if (name.trim() === '') {
      setMessage('账户名称不能为空。');
      return;
    }
    if (openingAmount.trim() !== '') {
      const decimals = currencyMinorUnits(currency);
      const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
      if (!pattern.test(openingAmount.trim())) {
        setMessage(`期初金额需符合 ${currency} 精度（最多 ${decimals} 位小数）。`);
        return;
      }
    }
    // 类型矩阵由 TYPE_MATRIX 在 UI 层约束；生成类型把矩阵建模为逐大类交叉类型，这里按运行时不变量收窄。
    const body = {
      accountClass,
      accountType,
      name: name.trim(),
      currency,
      openingBalance: openingAmount.trim() === ''
        ? null
        : { amount: openingAmount.trim(), businessAt: openingBusinessAt, note: null },
    } as CreateAccountRequest;
    setSubmitting(true);
    setMessage(null);
    try {
      await mobileAccountsApiClient.createAccount(idempotencyKeyRef.current, body);
      router.replace('/accounts');
    } catch (error) {
      const problem = (error as { problem?: { detail?: string; title?: string } }).problem;
      setMessage(problem?.detail ?? problem?.title ?? '创建失败，请稍后重试。');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="返回账户列表" onPress={() => router.back()} testID="account-create-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">创建账户</Text>

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">大类</Text>
        <View className="flex-row gap-2">
          {(['ASSET', 'INVESTMENT', 'LIABILITY'] as const).map((option) => (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityState={{ selected: accountClass === option }}
              onPress={() => {
                setAccountClass(option);
                setAccountType(TYPE_MATRIX[option][0].value);
              }}
              testID={`account-create-class-${option}`}
              className={`min-h-11 flex-1 items-center justify-center rounded-lg border ${accountClass === option ? 'border-accent bg-accent' : 'border-accent/40'}`}
            >
              <Text className={accountClass === option ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>
                {option === 'ASSET' ? '资产' : option === 'INVESTMENT' ? '投资' : '负债'}
              </Text>
            </Pressable>
          ))}
        </View>

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">子类型</Text>
        <View className="flex-row flex-wrap gap-2">
          {TYPE_MATRIX[accountClass].map((option) => (
            <Pressable
              key={option.value}
              accessibilityRole="button"
              accessibilityState={{ selected: accountType === option.value }}
              onPress={() => setAccountType(option.value)}
              testID={`account-create-type-${option.value}`}
              className={`min-h-11 flex-1 items-center justify-center rounded-lg border px-2 ${accountType === option.value ? 'border-accent bg-accent' : 'border-accent/40'}`}
            >
              <Text className={accountType === option.value ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{option.label}</Text>
            </Pressable>
          ))}
        </View>

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">账户名称</Text>
        <TextInput value={name} onChangeText={setName} testID="account-create-name"
          placeholder="例如：招商银行工资卡"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">币种</Text>
        <View className="flex-row gap-2">
          {CURRENCIES.map((code) => (
            <Pressable
              key={code}
              accessibilityRole="button"
              accessibilityState={{ selected: currency === code }}
              onPress={() => setCurrency(code)}
              testID={`account-create-currency-${code}`}
              className={`min-h-11 flex-1 items-center justify-center rounded-lg border ${currency === code ? 'border-accent bg-accent' : 'border-accent/40'}`}
            >
              <Text className={currency === code ? 'font-semibold text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{code}</Text>
            </Pressable>
          ))}
        </View>

        <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">期初金额（可选，{currency}）</Text>
        <TextInput value={openingAmount} onChangeText={setOpeningAmount} testID="account-create-opening"
          placeholder="0.00" keyboardType="decimal-pad"
          className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />

        {message ? (
          <Text accessibilityRole="alert" testID="account-create-message" className="text-sm text-destructive">{message}</Text>
        ) : null}

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="创建账户"
          accessibilityState={{ disabled: submitting }}
          disabled={submitting}
          onPress={() => void submit()}
          testID="account-create-submit"
          className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting ? 'opacity-50' : 'active:opacity-70'}`}
        >
          <Text className="font-bold text-canvas-dark">{submitting ? '正在创建…' : '创建账户'}</Text>
        </Pressable>
        <Text className="text-xs text-muted-light dark:text-muted-dark">
          创建请求携带幂等键；期初余额在创建事务内原子入账。
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}
