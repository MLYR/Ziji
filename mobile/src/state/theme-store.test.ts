import { useThemeStore } from './theme-store';

describe('theme store', () => {
  beforeEach(() => {
    // 每个用例恢复默认主题，隔离 Zustand 单例状态。
    useThemeStore.setState({ preference: 'system' });
  });

  it('updates only the local UI preference', () => {
    // 直接验证 vanilla Zustand 状态；此处没有 React 渲染，无需进入异步 act 作用域。
    useThemeStore.getState().setPreference('dark');

    expect(useThemeStore.getState().preference).toBe('dark');
  });
});
