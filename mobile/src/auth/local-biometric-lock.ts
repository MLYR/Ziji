import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';

const BIOMETRIC_LOCK_ENABLED_KEY = 'ziji.biometric-lock-enabled';

// 归一化后的本地生物识别结果；业务层不得依赖 expo 原始错误字符串。
export type LocalBiometricOutcome =
  | 'SUCCESS'
  | 'UNAVAILABLE'
  | 'NOT_ENROLLED'
  | 'CANCELLED'
  | 'LOCKED_OUT'
  | 'FAILED'
  | 'ERROR';

export interface LocalBiometricCapability {
  hasHardware: boolean;
  isEnrolled: boolean;
}

export interface LocalBiometricAuthenticateResult {
  success: boolean;
  error?: string;
}

export interface LocalBiometricAuthenticator {
  hasHardwareAsync(): Promise<boolean>;
  isEnrolledAsync(): Promise<boolean>;
  authenticateAsync(options: { disableDeviceFallback?: boolean; promptMessage?: string }): Promise<LocalBiometricAuthenticateResult>;
}

export interface LocalBiometricLockSettings {
  read(): Promise<string | null>;
  write(value: string): Promise<void>;
  clear(): Promise<void>;
}

// 生物识别只作为读取/使用本地认证材料之前的应用级本地解锁门禁，不构成服务端登录方式。
export class LocalBiometricLock {
  private inFlightUnlock: Promise<LocalBiometricOutcome> | null = null;

  constructor(
    private readonly authenticator: LocalBiometricAuthenticator,
    private readonly settings: LocalBiometricLockSettings,
  ) {}

  // 本机锁偏好读取失败时抛错，调用方必须 fail-closed 处理，不得按未启用放行。
  async isEnabled(): Promise<boolean> {
    const stored = await this.settings.read();
    return stored === 'true';
  }

  async checkCapability(): Promise<LocalBiometricCapability> {
    const [hasHardware, isEnrolled] = await Promise.all([
      this.authenticator.hasHardwareAsync(),
      this.authenticator.isEnrolledAsync(),
    ]);
    return { hasHardware, isEnrolled };
  }

  // 只有设备支持、已录入且当场完成一次成功认证后，才持久化启用标记。
  async enable(): Promise<LocalBiometricOutcome> {
    let capability: LocalBiometricCapability;
    try {
      capability = await this.checkCapability();
    } catch {
      return 'ERROR';
    }
    if (!capability.hasHardware) return 'UNAVAILABLE';
    if (!capability.isEnrolled) return 'NOT_ENROLLED';

    // 与 unlock 共用同一个 in-flight 认证槽位：启用 prompt 期间 isAuthenticating 为真，
    // 外部 AppState 监听不会把 prompt 自身的 inactive 误判为需要重新锁定。
    const outcome = await this.shareInFlightAuthentication();
    if (outcome !== 'SUCCESS') return outcome;

    try {
      // 启用标记与刷新凭据分离：只保存布尔偏好，不落任何生物识别模板或原生认证结果。
      await this.settings.write('true');
    } catch {
      return 'ERROR';
    }
    return 'SUCCESS';
  }

  // 并发调用合并到同一个原生认证任务，同一时刻最多弹出一个系统认证框。
  async unlock(): Promise<LocalBiometricOutcome> {
    if (this.inFlightUnlock) return this.inFlightUnlock;
    const task = this.unlockInternal().finally(() => {
      this.inFlightUnlock = null;
    });
    this.inFlightUnlock = task;
    return task;
  }

  // 是否有原生认证任务在途；生物识别 prompt 自身会触发 AppState 变化，外部用它避免递归锁定。
  isAuthenticating(): boolean {
    return this.inFlightUnlock !== null;
  }

  // 关闭只清除本机锁偏好，不触碰刷新凭据或服务端会话。
  async disable(): Promise<void> {
    await this.settings.clear();
  }

  private async unlockInternal(): Promise<LocalBiometricOutcome> {
    let capability: LocalBiometricCapability;
    try {
      capability = await this.checkCapability();
    } catch {
      return 'ERROR';
    }
    if (!capability.hasHardware) return 'UNAVAILABLE';
    if (!capability.isEnrolled) return 'NOT_ENROLLED';
    return this.authenticateOnce();
  }

  // enable 复用 unlock 已建立的 in-flight 任务；无在途任务时独占创建，不能反向等待自身任务造成死锁。
  private shareInFlightAuthentication(): Promise<LocalBiometricOutcome> {
    if (this.inFlightUnlock) return this.inFlightUnlock;
    const task = this.authenticateOnce().finally(() => {
      this.inFlightUnlock = null;
    });
    this.inFlightUnlock = task;
    return task;
  }

  private async authenticateOnce(): Promise<LocalBiometricOutcome> {
    let result: LocalBiometricAuthenticateResult;
    try {
      result = await this.authenticator.authenticateAsync({
        // 关闭系统设备密码回退；失败路径必须由资迹自己提供“重新登录”，不能由系统放行。
        disableDeviceFallback: true,
        promptMessage: '解锁资迹应用',
      });
    } catch {
      return 'ERROR';
    }
    if (result.success) return 'SUCCESS';
    switch (result.error) {
      case 'user_cancel':
      case 'app_cancel':
      case 'system_cancel':
      case 'user_fallback':
        return 'CANCELLED';
      case 'lockout':
        return 'LOCKED_OUT';
      case 'not_available':
        return 'UNAVAILABLE';
      case 'not_enrolled':
        return 'NOT_ENROLLED';
      default:
        // authentication_failed、timeout、unable_to_process、invalid_context、unknown 等均视为普通失败。
        return 'FAILED';
    }
  }
}

export const localBiometricLock = new LocalBiometricLock(
  LocalAuthentication,
  {
    // 锁偏好只进入系统安全存储，不落入 SQLite、Zustand、日志或普通配置。
    read: () => SecureStore.getItemAsync(BIOMETRIC_LOCK_ENABLED_KEY),
    write: (value) =>
      SecureStore.setItemAsync(BIOMETRIC_LOCK_ENABLED_KEY, value, {
        keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
      }),
    clear: () => SecureStore.deleteItemAsync(BIOMETRIC_LOCK_ENABLED_KEY),
  },
);
