import { SafeAreaView } from 'react-native-safe-area-context';
import { Pressable, Text, View } from 'react-native';

import { type ThemePreference, useThemeStore } from '@/state/theme-store';

const themeOptions: readonly ThemePreference[] = ['system', 'light', 'dark'];

export default function HomeScreen() {
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);

  return (
    <SafeAreaView className="flex-1 bg-canvas-light dark:bg-canvas-dark">
      <View className="flex-1 justify-center px-7">
        <View className="h-11 w-11 items-center justify-center rounded-xl bg-accent">
          <Text className="text-lg font-extrabold text-canvas-dark">Z</Text>
        </View>
        <Text className="mt-5 text-sm text-muted-light dark:text-muted-dark">资迹 Ziji · Mobile</Text>
        <Text className="mt-3 text-4xl font-bold leading-tight text-ink-light dark:text-ink-dark">
          财务事实清晰，进度随时可见。
        </Text>
        <Text className="mt-4 text-base leading-6 text-muted-light dark:text-muted-dark">
          Expo Router、离线缓存与安全凭据基座已经就绪。
        </Text>
        <View className="mt-7 flex-row gap-2" testID="theme-options">
          {themeOptions.map((option) => (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityState={{ selected: preference === option }}
              className={`rounded-full px-4 py-2 ${preference === option ? 'bg-accent' : 'bg-surface-light dark:bg-surface-dark'}`}
              onPress={() => setPreference(option)}
              testID={`theme-${option}`}
            >
              <Text className="text-sm font-medium text-ink-light dark:text-ink-dark">{option}</Text>
            </Pressable>
          ))}
        </View>
      </View>
    </SafeAreaView>
  );
}
