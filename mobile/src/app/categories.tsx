import { useRouter } from 'expo-router';
import { Pressable, ScrollView, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { CategoryManager } from '@/categories/category-manager';
import { mobileCategoryApiClient } from '@/auth/default-auth-session';

/** 分类与标签管理路由。 */
export default function CategoriesRoute() {
  const router = useRouter();

  return (
    <SafeAreaView style={{ flex: 1 }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="返回" onPress={() => router.back()} testID="categories-back">
          <Text className="text-base text-accent">返回</Text>
        </Pressable>
        <Text className="text-2xl font-bold text-ink-light dark:text-ink-dark" accessibilityRole="header">分类与标签</Text>
        <CategoryManager
          api={mobileCategoryApiClient}
          ids={{
            category: (globalThis.crypto as Crypto).randomUUID(),
            tag: (globalThis.crypto as Crypto).randomUUID(),
          }}
        />
      </ScrollView>
    </SafeAreaView>
  );
}
