import { useRouter } from 'expo-router';
import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mobileAccountsApiClient, mobileInvestmentApiClient } from '@/auth/default-auth-session';
import { InvestmentScreen } from '@/investments/investment-screen';

/** 投资入口路由：概览、行情质量、产品搜索、投资录入和收益月历共用当前 Mobile Bearer 会话。 */
export default function InvestmentsRoute() {
  const router = useRouter();

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <View className="flex-1">
        <Pressable accessibilityRole="button" accessibilityLabel="返回首页" onPress={() => router.back()} testID="investments-back" className="px-4 pt-2">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <InvestmentScreen
          api={mobileInvestmentApiClient}
          accountsApi={mobileAccountsApiClient}
          // 新增文件路由的本地 typed-routes 缓存尚未包含该路径，运行时仍由 Expo Router 按文件路由解析。
          onOpenAccount={(accountId) => router.push({ pathname: '/investment-detail', params: { id: accountId } } as never)}
          onOpenAccounts={() => router.push('/accounts')}
        />
      </View>
    </SafeAreaView>
  );
}
