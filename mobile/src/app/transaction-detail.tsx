import { useLocalSearchParams, useRouter } from 'expo-router';
import { Pressable, ScrollView, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileTransactionApiClient } from '@/auth/default-auth-session';
import { TransactionDetailScreen } from '@/ledger/transaction-detail-screen';

/** 交易详情路由：路径 ID 调用类型化 API。 */
export default function TransactionDetailRoute() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const transactionId = typeof params.id === 'string' ? params.id : null;
  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 12, gap: 8 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="返回" onPress={() => router.back()} testID="transaction-detail-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <TransactionDetailScreen
          transactionId={transactionId}
          getTransaction={(id) => mobileTransactionApiClient.getTransaction(id)}
          reviseTransaction={(id, etag, key, body) => mobileTransactionApiClient.reviseTransaction(id, etag, key, body)}
          reverseTransaction={(id, etag, key, body) => mobileTransactionApiClient.reverseTransaction(id, etag, key, body)}
        />
      </ScrollView>
    </SafeAreaView>
  );
}
