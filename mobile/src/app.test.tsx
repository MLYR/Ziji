// 测试文件放在 Expo Router 路由目录外，避免 Jest 测试被原生路由上下文打包。
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { AppState } from 'react-native';

import { ApiClientError } from '@/api/api-client';
import { useThemeStore } from '@/state/theme-store';

const unauthenticatedState = { errorMessage: null, session: null, status: 'UNAUTHENTICATED' as const };
const authenticatedState = {
  errorMessage: null,
  session: { id: 'session-1', deviceId: 'device-1', deviceName: 'Ziji Mobile', createdAt: '2026-08-17T00:00:00Z', lastSeenAt: '2026-08-17T00:00:00Z', status: 'ACTIVE' as const },
  status: 'AUTHENTICATED' as const,
  userId: 'user-1',
};

type MockAuthState = typeof unauthenticatedState | typeof authenticatedState;

const mockAuthenticationSession = {
  getState: jest.fn((): MockAuthState => unauthenticatedState),
  register: jest.fn(),
  requestRegistrationChallenge: jest.fn(),
  restore: jest.fn(),
  signIn: jest.fn(),
  signOut: jest.fn(),
  subscribe: jest.fn(),
};

const mockLocalBiometricLock = {
  isEnabled: jest.fn(),
  checkCapability: jest.fn(),
  enable: jest.fn(),
  unlock: jest.fn(),
  isAuthenticating: jest.fn(),
  disable: jest.fn(),
};

jest.mock('@/auth/default-auth-session', () => ({
  // Jest 会提升 mock 工厂；通过转发器延迟访问初始化后的测试替身。
  mobileAuthenticationSession: {
    getState: () => mockAuthenticationSession.getState(),
    register: (request: unknown, idempotencyKey: unknown) => mockAuthenticationSession.register(request, idempotencyKey),
    requestRegistrationChallenge: (email: unknown) => mockAuthenticationSession.requestRegistrationChallenge(email),
    restore: () => mockAuthenticationSession.restore(),
    signIn: (email: unknown, password: unknown) => mockAuthenticationSession.signIn(email, password),
    signOut: () => mockAuthenticationSession.signOut(),
    subscribe: (listener: unknown) => mockAuthenticationSession.subscribe(listener),
  },
}));

jest.mock('@/auth/auth-session', () => ({
  createRegistrationIdempotencyKey: () => 'register-test-key',
}));

jest.mock('@/auth/local-biometric-lock', () => ({
  localBiometricLock: {
    isEnabled: () => mockLocalBiometricLock.isEnabled(),
    checkCapability: () => mockLocalBiometricLock.checkCapability(),
    enable: () => mockLocalBiometricLock.enable(),
    unlock: () => mockLocalBiometricLock.unlock(),
    isAuthenticating: () => mockLocalBiometricLock.isAuthenticating(),
    disable: () => mockLocalBiometricLock.disable(),
  },
}));

// 已认证面板会挂载同步面板并打开 SQLite/同步任务；本文件只验证认证与本地锁行为。
jest.mock('@/sync/sync-status-panel', () => ({
  SyncStatusPanel: () => null,
}));

// AppState 事件由测试显式驱动，验证前后台本地锁生命周期。
const appStateListeners: Array<(state: string) => void> = [];
jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, listener: (state: string) => void) => {
  appStateListeners.push(listener);
  return {
    remove: () => {
      const index = appStateListeners.indexOf(listener);
      if (index >= 0) appStateListeners.splice(index, 1);
    },
  };
}) as typeof AppState.addEventListener);

function emitAppState(state: 'active' | 'background' | 'inactive'): void {
  [...appStateListeners].forEach((listener) => listener(state));
}

// 认证单例必须在替身初始化后再加载，避免静态 import 早于 Jest mock 数据创建。
const AuthenticationScreen = require('./app/index').default as typeof import('./app/index').default;

function rateLimitedError(): ApiClientError {
  return new ApiClientError(
    { code: 'RATE_LIMITED', requestId: 'request-1', status: 429, title: '请求过于频繁', type: 'about:blank' },
    31,
  );
}

function fieldError(): ApiClientError {
  return new ApiClientError({
    code: 'VALIDATION_ERROR',
    fieldErrors: [{ code: 'INVALID', field: 'verificationCode', message: '验证码无效。' }],
    requestId: 'request-1',
    status: 400,
    title: '输入无效',
    type: 'about:blank',
  });
}

describe('AuthenticationScreen', () => {
  let authStateListener: ((state: MockAuthState) => void) | null;

  beforeEach(() => {
    jest.clearAllMocks();
    useThemeStore.setState({ preference: 'system' });
    authStateListener = null;
    appStateListeners.length = 0;
    mockAuthenticationSession.getState.mockReturnValue(unauthenticatedState);
    mockAuthenticationSession.restore.mockResolvedValue(unauthenticatedState);
    // clearAllMocks 只清调用记录不清实现；逐个重置默认实现，防止上个用例的 pending mock 泄漏。
    mockAuthenticationSession.signIn.mockResolvedValue(undefined);
    mockAuthenticationSession.signOut.mockResolvedValue({ localCredentialsCleared: true, remoteSessionRevoked: true });
    mockAuthenticationSession.register.mockResolvedValue(undefined);
    mockAuthenticationSession.requestRegistrationChallenge.mockResolvedValue(600);
    mockAuthenticationSession.subscribe.mockImplementation((listener) => {
      authStateListener = listener;
      return () => {
        authStateListener = null;
      };
    });
    mockLocalBiometricLock.isEnabled.mockResolvedValue(false);
    mockLocalBiometricLock.checkCapability.mockResolvedValue({ hasHardware: true, isEnrolled: true });
    mockLocalBiometricLock.enable.mockResolvedValue('SUCCESS');
    mockLocalBiometricLock.unlock.mockResolvedValue('SUCCESS');
    mockLocalBiometricLock.isAuthenticating.mockReturnValue(false);
    mockLocalBiometricLock.disable.mockResolvedValue(undefined);
  });

  it('登录提交期间显示可读 loading 状态并阻止重复提交', async () => {
    let resolveSignIn: () => void = () => undefined;
    mockAuthenticationSession.signIn.mockImplementation(() => new Promise<void>((resolve) => { resolveSignIn = resolve; }));
    const view = await render(<AuthenticationScreen />);

    await fireEvent.changeText(view.getByLabelText('邮箱地址'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('密码'), 'password');
    await fireEvent.press(view.getByTestId('auth-submit'));

    expect(view.getByLabelText('正在提交认证信息').props.accessibilityState).toEqual({ busy: true, disabled: true });
    await fireEvent.press(view.getByTestId('auth-submit'));
    expect(mockAuthenticationSession.signIn).toHaveBeenCalledTimes(1);

    await act(async () => { resolveSignIn(); });
  });

  it('安全存储失败显示可恢复的设备解锁提示', async () => {
    const error = new Error('无法安全保存登录凭据，请解锁设备后重试。');
    error.name = 'SecureCredentialWriteError';
    mockAuthenticationSession.signIn.mockRejectedValue(error);
    const view = await render(<AuthenticationScreen />);

    await fireEvent.changeText(view.getByLabelText('邮箱地址'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('密码'), 'password');
    await fireEvent.press(view.getByTestId('auth-submit'));

    await view.findByText('无法安全保存登录凭据，请解锁设备后重试。');
  });

  it('对验证码请求显示 Retry-After，且按钮与输入框可访问', async () => {
    mockAuthenticationSession.requestRegistrationChallenge.mockRejectedValue(rateLimitedError());
    const view = await render(<AuthenticationScreen />);

    await fireEvent.press(view.getByLabelText('切换到注册'));
    await fireEvent.changeText(view.getByLabelText('邮箱地址'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('密码'), 'password');
    await fireEvent.press(view.getByTestId('auth-submit'));

    await waitFor(() => expect(view.getByRole('alert').props.children).toContain('31'));
    expect(view.getByLabelText('邮箱地址').props.editable).toBe(true);
    expect(view.getByLabelText('切换到登录').props.accessibilityRole).toBe('tab');
  });

  it('注册字段错误可读，同一次安全重试复用 Idempotency-Key', async () => {
    mockAuthenticationSession.requestRegistrationChallenge.mockResolvedValue(600);
    mockAuthenticationSession.register
      .mockRejectedValueOnce(fieldError())
      .mockResolvedValueOnce(undefined);
    const view = await render(<AuthenticationScreen />);

    await fireEvent.press(view.getByLabelText('切换到注册'));
    await fireEvent.changeText(view.getByLabelText('邮箱地址'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('密码'), 'password');
    await fireEvent.press(view.getByTestId('auth-submit'));
    await view.findByLabelText('邮箱验证码');
    await fireEvent.changeText(view.getByLabelText('邮箱验证码'), '123456');
    await fireEvent.changeText(view.getByLabelText('昵称'), '资迹');
    await fireEvent.press(view.getByTestId('auth-submit'));

    await view.findByText('错误：验证码无效。');
    await fireEvent.press(view.getByTestId('auth-submit'));
    await waitFor(() => expect(mockAuthenticationSession.register).toHaveBeenCalledTimes(2));

    expect(mockAuthenticationSession.register.mock.calls.map(([, key]) => key)).toEqual(['register-test-key', 'register-test-key']);
  });

  it('主题按钮可访问，并依次呈现系统、浅色和深色状态', async () => {
    const view = await render(<AuthenticationScreen />);
    const themeButton = view.getByTestId('theme-toggle');

    expect(view.getByText('主题：系统')).toBeTruthy();
    await fireEvent.press(themeButton);
    expect(view.getByText('主题：浅色')).toBeTruthy();
    await fireEvent.press(themeButton);
    expect(view.getByText('主题：深色')).toBeTruthy();
    expect(themeButton.props.accessibilityRole).toBe('button');
  });

  it('未启用生物识别时启动直接走现有恢复流程', async () => {
    const view = await render(<AuthenticationScreen />);

    await waitFor(() => expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1));
    expect(view.queryByText('资迹已锁定')).toBeNull();
  });

  it('已启用生物识别时冷启动停在本地锁定，不读取凭据恢复会话', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    const view = await render(<AuthenticationScreen />);

    await view.findByText('资迹已锁定');
    await view.findByLabelText('使用生物识别解锁');
    await view.findByLabelText('重新登录');
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();
  });

  it('本地解锁成功后才补发一次现有 restore', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));

    await waitFor(() => expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1));
    expect(view.queryByText('资迹已锁定')).toBeNull();
  });

  it('生物识别失败保持锁定且可重试，重试成功后只恢复一次', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockLocalBiometricLock.unlock.mockResolvedValueOnce('FAILED').mockResolvedValueOnce('SUCCESS');
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText('生物识别验证未通过，请重试。');
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await waitFor(() => expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1));
  });

  it('生物识别取消或设备不可用时保持锁定并提示重新登录', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockLocalBiometricLock.unlock.mockResolvedValueOnce('CANCELLED').mockResolvedValueOnce('UNAVAILABLE');
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText('已取消生物识别验证，可重试或重新登录。');
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText('当前设备不支持生物识别，无法完成本地解锁，请重新登录后继续。');
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();
    expect(view.getByLabelText('重新登录')).toBeTruthy();
  });

  it('重新登录清除本机认证材料与锁偏好并回到登录表单', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockAuthenticationSession.signOut.mockResolvedValue({ localCredentialsCleared: true, remoteSessionRevoked: false });
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-relogin'));

    await waitFor(() => expect(mockAuthenticationSession.signOut).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mockLocalBiometricLock.disable).toHaveBeenCalledTimes(1));
    await view.findByLabelText('邮箱地址');
    expect(view.queryByText('资迹已锁定')).toBeNull();
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();
  });

  it('本机锁设置读取失败时按已启用处理，不允许恢复会话', async () => {
    mockLocalBiometricLock.isEnabled.mockRejectedValue(new Error('secure store unavailable'));
    const view = await render(<AuthenticationScreen />);

    await view.findByText('资迹已锁定');
    await view.findByText('无法读取本机生物识别设置，可重新登录后继续。');
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();
  });

  it('连续快速点击解锁只产生一个认证任务，成功后只恢复一次', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    let resolveUnlock: (outcome: string) => void = () => undefined;
    mockLocalBiometricLock.unlock.mockImplementation(() => new Promise((resolve) => { resolveUnlock = resolve; }));
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await fireEvent.press(view.getByTestId('biometric-unlock'));
    expect(mockLocalBiometricLock.unlock).toHaveBeenCalledTimes(1);

    await act(async () => { resolveUnlock('SUCCESS'); });
    await waitFor(() => expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1));
  });

  it('前台返回时按本机锁重新锁定已认证内容，解锁后不重复恢复会话', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('已安全登录');
    expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1);

    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    await act(async () => {
      emitAppState('background');
      emitAppState('active');
    });

    await view.findByText('资迹已锁定');
    expect(view.queryByText('已安全登录')).toBeNull();
    expect(view.queryByLabelText('退出当前设备')).toBeNull();

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText('已安全登录');
    // 冷启动未等待本地锁，前台解锁不得再次读取刷新凭据恢复会话。
    expect(mockAuthenticationSession.restore).toHaveBeenCalledTimes(1);
  });

  it('生物识别 prompt 自身引起的 AppState 变化不会触发重新锁定', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('已安全登录');

    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockLocalBiometricLock.isAuthenticating.mockReturnValue(true);
    await act(async () => {
      emitAppState('inactive');
      emitAppState('active');
    });

    expect(view.queryByText('资迹已锁定')).toBeNull();
    expect(view.getByText('已安全登录')).toBeTruthy();
  });

  it('未认证状态下前台返回不触发本地锁定', async () => {
    const view = await render(<AuthenticationScreen />);
    await view.findByLabelText('邮箱地址');

    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    await act(async () => {
      emitAppState('background');
      emitAppState('active');
    });

    expect(view.queryByText('资迹已锁定')).toBeNull();
    expect(view.getByLabelText('邮箱地址')).toBeTruthy();
  });

  it('已认证用户启用生物识别，只有认证成功后才显示已开启', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    mockLocalBiometricLock.enable.mockResolvedValueOnce('FAILED').mockResolvedValueOnce('SUCCESS');
    const view = await render(<AuthenticationScreen />);
    await view.findByTestId('biometric-enable');

    await fireEvent.press(view.getByTestId('biometric-enable'));
    await view.findByText('生物识别验证未通过，未启用本地解锁。');
    expect(view.queryByText('关闭生物识别解锁')).toBeNull();

    await fireEvent.press(view.getByTestId('biometric-enable'));
    await waitFor(() => expect(mockLocalBiometricLock.enable).toHaveBeenCalledTimes(2));
    await view.findByText(/生物识别解锁已开启/);
    await view.findByTestId('biometric-disable');
  });

  it('设备不支持生物识别时不提供永远失败的启用按钮', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    mockLocalBiometricLock.checkCapability.mockResolvedValue({ hasHardware: false, isEnrolled: false });
    const view = await render(<AuthenticationScreen />);

    await view.findByText('当前设备不支持生物识别，无法启用本地解锁。');
    expect(view.queryByTestId('biometric-enable')).toBeNull();
  });

  it('设备未录入生物识别时提示先录入，不提供启用按钮', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    mockLocalBiometricLock.checkCapability.mockResolvedValue({ hasHardware: true, isEnrolled: false });
    const view = await render(<AuthenticationScreen />);

    await view.findByText('当前设备未录入生物识别，请先在系统设置中录入后再启用。');
    expect(view.queryByTestId('biometric-enable')).toBeNull();
  });

  it('已认证用户可以关闭生物识别，关闭后回到可启用状态', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText(/生物识别解锁已开启/);

    await fireEvent.press(view.getByTestId('biometric-disable'));
    await waitFor(() => expect(mockLocalBiometricLock.disable).toHaveBeenCalledTimes(1));
    await view.findByTestId('biometric-enable');
  });

  it('重新登录与解锁并发时，迟到的解锁成功不得恢复已失效会话', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    let resolveUnlock: (outcome: string) => void = () => undefined;
    mockLocalBiometricLock.unlock.mockImplementation(() => new Promise((resolve) => { resolveUnlock = resolve; }));
    mockAuthenticationSession.signOut.mockResolvedValue({ localCredentialsCleared: true, remoteSessionRevoked: false });
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await fireEvent.press(view.getByTestId('biometric-relogin'));
    await view.findByLabelText('邮箱地址');

    // 旧 unlock 在重新登录后才完成：结果必须被代次检查丢弃，不得恢复会话。
    await act(async () => { resolveUnlock('SUCCESS'); });
    expect(mockAuthenticationSession.restore).not.toHaveBeenCalled();
    expect(view.getByLabelText('邮箱地址')).toBeTruthy();
  });

  it('关闭生物识别验证未通过时不清除本机锁偏好', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText(/生物识别解锁已开启/);

    mockLocalBiometricLock.unlock.mockResolvedValueOnce('CANCELLED');
    await fireEvent.press(view.getByTestId('biometric-disable'));
    await view.findByText('关闭生物识别解锁前需要先完成本地验证。');
    expect(mockLocalBiometricLock.disable).not.toHaveBeenCalled();
  });

  it('设备生物识别已不可用时允许直接关闭本地锁', async () => {
    mockLocalBiometricLock.isEnabled.mockResolvedValue(true);
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    const view = await render(<AuthenticationScreen />);
    await view.findByText('资迹已锁定');

    await fireEvent.press(view.getByTestId('biometric-unlock'));
    await view.findByText(/生物识别解锁已开启/);

    // 设备已无可验证对象时，关闭一个本就无法工作的锁不要求验证。
    mockLocalBiometricLock.unlock.mockResolvedValueOnce('UNAVAILABLE');
    await fireEvent.press(view.getByTestId('biometric-disable'));
    await waitFor(() => expect(mockLocalBiometricLock.disable).toHaveBeenCalledTimes(1));
    await view.findByTestId('biometric-enable');
  });

  it('邮箱密码登录成功后清除旧主体的本机锁偏好', async () => {
    const view = await render(<AuthenticationScreen />);

    await fireEvent.changeText(view.getByLabelText('邮箱地址'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('密码'), 'password');
    await fireEvent.press(view.getByTestId('auth-submit'));

    await waitFor(() => expect(mockAuthenticationSession.signIn).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mockLocalBiometricLock.disable).toHaveBeenCalledTimes(1));
  });

  it('退出当前设备成功清除本机凭据时一并清除锁偏好', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    mockAuthenticationSession.signOut.mockResolvedValue({ localCredentialsCleared: true, remoteSessionRevoked: true });
    const view = await render(<AuthenticationScreen />);
    await view.findByLabelText('退出当前设备');

    await fireEvent.press(view.getByLabelText('退出当前设备'));

    await waitFor(() => expect(mockAuthenticationSession.signOut).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mockLocalBiometricLock.disable).toHaveBeenCalledTimes(1));
  });

  it('本机凭据清除失败时保留本地锁偏好，继续保护遗留凭据', async () => {
    mockAuthenticationSession.getState.mockReturnValue(authenticatedState);
    mockAuthenticationSession.signOut.mockResolvedValue({ localCredentialsCleared: false, remoteSessionRevoked: false });
    const view = await render(<AuthenticationScreen />);
    await view.findByLabelText('退出当前设备');

    await fireEvent.press(view.getByLabelText('退出当前设备'));

    await waitFor(() => expect(mockAuthenticationSession.signOut).toHaveBeenCalledTimes(1));
    expect(mockLocalBiometricLock.disable).not.toHaveBeenCalled();
  });
});
