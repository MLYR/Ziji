import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAuthApiClient, mobileTransactionApiClient } from '@/auth/default-auth-session';
import { QuickRecordScreen } from '@/ledger/quick-record-screen';

/** 快速记账路由：币种与时区取自服务端用户资料；成功后可直接打开交易详情。 */
export default function QuickRecordRoute() {
  const router = useRouter();
  const [profile, setProfile] = useState<{ baseCurrency: 'CNY' | 'USD' | 'HKD' | 'JPY' | 'EUR'; timezone: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    mobileAuthApiClient.getCurrentUser()
      .then((envelope) => {
        if (!cancelled) {
          setProfile({
            baseCurrency: envelope.data.baseCurrency as 'CNY' | 'USD' | 'HKD' | 'JPY' | 'EUR',
            timezone: envelope.data.timezone,
          });
        }
      })
      .catch(() => {
        if (!cancelled) setProfile(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="返回首页"
          onPress={() => router.back()}
          testID="quick-record-back"
        >
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        {profile ? (
          <QuickRecordScreen
            currency={profile.baseCurrency}
            timezone={profile.timezone}
            keyFor={() => globalThis.crypto.randomUUID()}
            onSuccess={(transactionId) => {
              router.push({ pathname: '/transaction-detail', params: { id: transactionId } });
            }}
            createTransaction={(key, body) => mobileTransactionApiClient.createTransaction(key, body)}
          />
        ) : (
          <Text className="text-base text-muted-light dark:text-muted-dark">正在读取用户资料…</Text>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
