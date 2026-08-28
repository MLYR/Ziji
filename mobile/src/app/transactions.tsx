import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';

import { mobileTransactionApiClient } from '@/auth/default-auth-session';
import { TransactionsScreen } from '@/ledger/transactions-screen';

/** 流水列表路由。 */
export default function TransactionsRoute() {
  const router = useRouter();
  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <View className="flex-1 px-3">
        <Pressable accessibilityRole="button" accessibilityLabel="返回首页" onPress={() => router.back()} testID="transactions-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <TransactionsScreen
          listTransactions={(limit, filters) => mobileTransactionApiClient.listTransactions(limit, filters)}
          onViewTransaction={(transactionId) => router.push({ pathname: '/transaction-detail', params: { id: transactionId } })}
          onOpenQuickRecord={() => router.push('/quick-record')}
        />
      </View>
    </SafeAreaView>
  );
}
