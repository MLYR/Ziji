import {
  LocalBiometricLock,
  type LocalBiometricAuthenticateResult,
  type LocalBiometricAuthenticator,
  type LocalBiometricLockSettings,
} from '@/auth/local-biometric-lock';

function authenticateMock(impl: LocalBiometricAuthenticator['authenticateAsync']): jest.MockedFunction<LocalBiometricAuthenticator['authenticateAsync']> {
  return jest.fn(impl);
}

function createAuthenticator(overrides: Partial<jest.Mocked<LocalBiometricAuthenticator>> = {}): jest.Mocked<LocalBiometricAuthenticator> {
  return {
    hasHardwareAsync: jest.fn(async () => true),
    isEnrolledAsync: jest.fn(async () => true),
    authenticateAsync: authenticateMock(async () => ({ success: true })),
    ...overrides,
  };
}

function createSettings(initial: string | null = null): jest.Mocked<LocalBiometricLockSettings> & { stored: () => string | null } {
  let stored = initial;
  return {
    read: jest.fn(async () => stored),
    write: jest.fn(async (value: string) => { stored = value; }),
    clear: jest.fn(async () => { stored = null; }),
    stored: () => stored,
  };
}

function failure(error: string): LocalBiometricAuthenticateResult {
  return { success: false, error };
}

describe('LocalBiometricLock', () => {
  it('设备支持且已录入时，认证成功后才持久化启用标记', async () => {
    const authenticator = createAuthenticator();
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('SUCCESS');

    expect(authenticator.authenticateAsync).toHaveBeenCalledTimes(1);
    expect(authenticator.authenticateAsync).toHaveBeenCalledWith(expect.objectContaining({ disableDeviceFallback: true }));
    expect(settings.write).toHaveBeenCalledWith('true');
    expect(settings.stored()).toBe('true');
  });

  it('没有生物识别硬件时启用失败且不持久化', async () => {
    const authenticator = createAuthenticator({ hasHardwareAsync: jest.fn(async () => false) });
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('UNAVAILABLE');
    expect(authenticator.authenticateAsync).not.toHaveBeenCalled();
    expect(settings.write).not.toHaveBeenCalled();
  });

  it('未录入生物识别时启用失败且不持久化', async () => {
    const authenticator = createAuthenticator({ isEnrolledAsync: jest.fn(async () => false) });
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('NOT_ENROLLED');
    expect(authenticator.authenticateAsync).not.toHaveBeenCalled();
    expect(settings.write).not.toHaveBeenCalled();
  });

  it('认证失败、取消或锁定时启用失败且不持久化', async () => {
    const cases: Array<[string, string]> = [
      ['authentication_failed', 'FAILED'],
      ['user_cancel', 'CANCELLED'],
      ['app_cancel', 'CANCELLED'],
      ['system_cancel', 'CANCELLED'],
      ['user_fallback', 'CANCELLED'],
      ['lockout', 'LOCKED_OUT'],
      ['timeout', 'FAILED'],
      ['unable_to_process', 'FAILED'],
      ['invalid_context', 'FAILED'],
      ['unknown', 'FAILED'],
      ['not_available', 'UNAVAILABLE'],
      ['not_enrolled', 'NOT_ENROLLED'],
    ];
    for (const [error, expected] of cases) {
      const authenticator = createAuthenticator({ authenticateAsync: authenticateMock(async () => failure(error)) });
      const settings = createSettings();
      const lock = new LocalBiometricLock(authenticator, settings);

      await expect(lock.enable()).resolves.toBe(expected);
      expect(settings.write).not.toHaveBeenCalled();
    }
  });

  it('原生能力检测抛异常时启用返回 ERROR 且不持久化', async () => {
    const authenticator = createAuthenticator({ hasHardwareAsync: jest.fn(async () => { throw new Error('native failure'); }) });
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('ERROR');
    expect(settings.write).not.toHaveBeenCalled();
  });

  it('原生认证抛异常时返回 ERROR 且不持久化', async () => {
    const authenticator = createAuthenticator({ authenticateAsync: authenticateMock(async () => { throw new Error('native failure'); }) });
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('ERROR');
    expect(settings.write).not.toHaveBeenCalled();
  });

  it('启用标记写入失败时返回 ERROR，不能视为已启用', async () => {
    const authenticator = createAuthenticator();
    const settings = createSettings();
    settings.write.mockRejectedValueOnce(new Error('secure store unavailable'));
    const lock = new LocalBiometricLock(authenticator, settings);

    await expect(lock.enable()).resolves.toBe('ERROR');
    expect(settings.stored()).toBeNull();
  });

  it('解锁成功返回 SUCCESS', async () => {
    const lock = new LocalBiometricLock(createAuthenticator(), createSettings('true'));
    await expect(lock.unlock()).resolves.toBe('SUCCESS');
  });

  it('解锁时设备无硬件或未录入直接返回对应状态，不发起原生认证', async () => {
    const noHardware = createAuthenticator({ hasHardwareAsync: jest.fn(async () => false) });
    await expect(new LocalBiometricLock(noHardware, createSettings('true')).unlock()).resolves.toBe('UNAVAILABLE');
    expect(noHardware.authenticateAsync).not.toHaveBeenCalled();

    const notEnrolled = createAuthenticator({ isEnrolledAsync: jest.fn(async () => false) });
    await expect(new LocalBiometricLock(notEnrolled, createSettings('true')).unlock()).resolves.toBe('NOT_ENROLLED');
    expect(notEnrolled.authenticateAsync).not.toHaveBeenCalled();
  });

  it('解锁失败、取消、锁定、不可用等结果都不会返回 SUCCESS', async () => {
    const cases: Array<[string, string]> = [
      ['authentication_failed', 'FAILED'],
      ['user_cancel', 'CANCELLED'],
      ['system_cancel', 'CANCELLED'],
      ['user_fallback', 'CANCELLED'],
      ['lockout', 'LOCKED_OUT'],
      ['not_available', 'UNAVAILABLE'],
      ['not_enrolled', 'NOT_ENROLLED'],
      ['timeout', 'FAILED'],
      ['unknown', 'FAILED'],
    ];
    for (const [error, expected] of cases) {
      const authenticator = createAuthenticator({ authenticateAsync: authenticateMock(async () => failure(error)) });
      await expect(new LocalBiometricLock(authenticator, createSettings('true')).unlock()).resolves.toBe(expected);
    }
  });

  it('并发解锁合并为同一个原生认证任务', async () => {
    let resolveAuthenticate: (result: LocalBiometricAuthenticateResult) => void = () => undefined;
    const pending = new Promise<LocalBiometricAuthenticateResult>((resolve) => { resolveAuthenticate = resolve; });
    const authenticator = createAuthenticator({ authenticateAsync: authenticateMock(() => pending) });
    const lock = new LocalBiometricLock(authenticator, createSettings('true'));

    const first = lock.unlock();
    const second = lock.unlock();
    expect(lock.isAuthenticating()).toBe(true);

    resolveAuthenticate({ success: true });
    await expect(first).resolves.toBe('SUCCESS');
    await expect(second).resolves.toBe('SUCCESS');
    expect(authenticator.authenticateAsync).toHaveBeenCalledTimes(1);
    expect(lock.isAuthenticating()).toBe(false);
  });

  it('启用与解锁并发时复用同一个 in-flight 认证任务', async () => {
    let resolveAuthenticate: (result: LocalBiometricAuthenticateResult) => void = () => undefined;
    const pending = new Promise<LocalBiometricAuthenticateResult>((resolve) => { resolveAuthenticate = resolve; });
    const authenticator = createAuthenticator({ authenticateAsync: authenticateMock(() => pending) });
    const settings = createSettings();
    const lock = new LocalBiometricLock(authenticator, settings);

    const enabling = lock.enable();
    const unlocking = lock.unlock();
    expect(lock.isAuthenticating()).toBe(true);

    resolveAuthenticate({ success: true });
    await expect(enabling).resolves.toBe('SUCCESS');
    await expect(unlocking).resolves.toBe('SUCCESS');
    // 同一时刻只有一个原生认证框，启用成功后正常持久化标记。
    expect(authenticator.authenticateAsync).toHaveBeenCalledTimes(1);
    expect(settings.stored()).toBe('true');
  });

  it('关闭只清除启用标记，不触碰其他本机凭据', async () => {
    const settings = createSettings('true');
    const lock = new LocalBiometricLock(createAuthenticator(), settings);

    await lock.disable();

    expect(settings.clear).toHaveBeenCalledTimes(1);
    expect(settings.stored()).toBeNull();
    await expect(lock.isEnabled()).resolves.toBe(false);
  });

  it('isEnabled 只认显式的启用标记', async () => {
    await expect(new LocalBiometricLock(createAuthenticator(), createSettings('true')).isEnabled()).resolves.toBe(true);
    await expect(new LocalBiometricLock(createAuthenticator(), createSettings(null)).isEnabled()).resolves.toBe(false);
    await expect(new LocalBiometricLock(createAuthenticator(), createSettings('false')).isEnabled()).resolves.toBe(false);
  });

  it('启用标记读取失败时抛错，由调用方 fail-closed 处理', async () => {
    const settings = createSettings('true');
    settings.read.mockRejectedValueOnce(new Error('secure store unavailable'));
    const lock = new LocalBiometricLock(createAuthenticator(), settings);

    await expect(lock.isEnabled()).rejects.toThrow('secure store unavailable');
  });
});
