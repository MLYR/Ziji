import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import { ApiClientError } from '@/api/api-client';
import { useThemeStore } from '@/state/theme-store';

const unauthenticatedState = { errorMessage: null, session: null, status: 'UNAUTHENTICATED' as const };

const mockAuthenticationSession = {
  getState: jest.fn(() => unauthenticatedState),
  register: jest.fn(),
  requestRegistrationChallenge: jest.fn(),
  restore: jest.fn(),
  signIn: jest.fn(),
  signOut: jest.fn(),
  subscribe: jest.fn(),
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

// 认证单例必须在替身初始化后再加载，避免静态 import 早于 Jest mock 数据创建。
const AuthenticationScreen = require('./index').default as typeof import('./index').default;

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
  beforeEach(() => {
    jest.clearAllMocks();
    useThemeStore.setState({ preference: 'system' });
    mockAuthenticationSession.getState.mockReturnValue(unauthenticatedState);
    mockAuthenticationSession.restore.mockResolvedValue(unauthenticatedState);
    mockAuthenticationSession.subscribe.mockReturnValue(() => undefined);
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
    const themeButton = view.getByLabelText('切换深浅主题');

    expect(view.getByText('主题：系统')).toBeTruthy();
    await fireEvent.press(themeButton);
    expect(view.getByText('主题：浅色')).toBeTruthy();
    await fireEvent.press(themeButton);
    expect(view.getByText('主题：深色')).toBeTruthy();
    expect(themeButton.props.accessibilityRole).toBe('button');
  });
});
