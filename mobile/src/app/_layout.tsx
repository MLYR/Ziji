import '../../global.css';

import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { useColorScheme as useNativeWindColorScheme } from 'nativewind';

import { useThemeStore } from '@/state/theme-store';

export default function RootLayout() {
  const preference = useThemeStore((state) => state.preference);
  const { setColorScheme } = useNativeWindColorScheme();

  useEffect(() => {
    // 将持久化前的界面偏好集中映射到 NativeWind，避免页面各自维护主题状态。
    setColorScheme(preference);
  }, [preference, setColorScheme]);

  return (
    <>
      <Stack screenOptions={{ headerShown: false }} />
      <StatusBar style={preference === 'light' ? 'dark' : 'light'} />
    </>
  );
}
