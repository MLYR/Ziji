import { useLocalSearchParams, useRouter } from 'expo-router';
import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAccountsApiClient, mobileInvestmentApiClient } from '@/auth/default-auth-session';
import { InvestmentDetailScreen } from '@/investments/investment-detail-screen';

/** 投资账户详情路由：路径参数只用于定位服务端账户，持仓和收益不在路由层复制。 */
export default function InvestmentDetailRoute() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const accountId = typeof params.id === 'string' ? params.id : null;

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <View className="flex-1">
        <Pressable accessibilityRole="button" accessibilityLabel="返回投资概览" onPress={() => router.back()} testID="investment-detail-back" className="px-4 pt-2">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <InvestmentDetailScreen accountId={accountId} api={mobileInvestmentApiClient} accountsApi={mobileAccountsApiClient} />
      </View>
    </SafeAreaView>
  );
}
