import { create } from 'zustand'

type Theme = 'dark' | 'light'

interface UiState {
  theme: Theme
  toggleTheme: () => void
}

// Zustand 仅保存主题等界面状态，不复制服务端财务事实。
export const useUiStore = create<UiState>((set) => ({
  theme: 'dark',
  toggleTheme: () => set(({ theme }) => ({ theme: theme === 'dark' ? 'light' : 'dark' })),
}))
