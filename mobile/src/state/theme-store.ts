import { create } from 'zustand';

export type ThemePreference = 'system' | 'light' | 'dark';

interface ThemeState {
  preference: ThemePreference;
  setPreference: (preference: ThemePreference) => void;
}

export const useThemeStore = create<ThemeState>((set) => ({
  preference: 'system',
  // Zustand 只保存界面偏好，不复制任何服务端财务事实。
  setPreference: (preference) => set({ preference }),
}));
