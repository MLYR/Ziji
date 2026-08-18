import type { components } from '@ziji/api-types';

import { ApiClientError, type MobileAuthApiClient, type RegisterRequest } from '@/api/api-client';
import type { SecureCredentialStore } from '@/storage/secure-credentials';

type Session = components['schemas']['Session'];

export type AuthenticationStatus = 'RESTORING' | 'UNAUTHENTICATED' | 'AUTHENTICATED' | 'RECOVERABLE_ERROR';

export interface MobileAuthenticationState {
  status: AuthenticationStatus;
  session: Session | null;
  userId: string | null;
  errorMessage: string | null;
}

export interface DeviceIdentity {
  deviceId: string;
  deviceName: string;
}

export interface DeviceIdentityProvider {
  get(): Promise<DeviceIdentity>;
}

export interface LogoutResult {
  localCredentialsCleared: boolean;
  remoteSessionRevoked: boolean;
}

const unauthenticatedState: MobileAuthenticationState = {
  status: 'UNAUTHENTICATED',
  session: null,
  userId: null,
  errorMessage: null,
};

function createOpaqueDeviceId(): string {
  const randomId = globalThis.crypto?.randomUUID?.();
  if (randomId) return `ziji-${randomId}`;

  // deviceId 不是身份凭据；此降级值只在缺少原生 UUID API 时提供稳定会话替换边界。
  return `ziji-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function createDeviceIdentityProvider(credentials: SecureCredentialStore, deviceName = 'Ziji Mobile'): DeviceIdentityProvider {
  const normalizedDeviceName = deviceName.normalize('NFKC').trim();
  if (normalizedDeviceName.length === 0 || normalizedDeviceName.length > 100) {
    throw new Error('设备名称必须为 1 到 100 个字符。');
  }

  return {
    async get() {
      const existingDeviceId = await credentials.readDeviceId();
      if (existingDeviceId && existingDeviceId.trim().length > 0 && existingDeviceId.length <= 200) {
        return { deviceId: existingDeviceId, deviceName: normalizedDeviceName };
      }

      const deviceId = createOpaqueDeviceId();
      // 先持久化再发起认证，避免一次失败重试意外创建多个稳定设备会话。
      await credentials.writeDeviceId(deviceId);
      return { deviceId, deviceName: normalizedDeviceName };
    },
  };
}

export class SecureCredentialWriteError extends Error {
  constructor() {
    super('无法安全保存登录凭据，请解锁设备后重试。');
    this.name = 'SecureCredentialWriteError';
  }
}

export function createRegistrationIdempotencyKey(): string {
  return `register-${globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`}`;
}

export class MobileAuthenticationSession {
  private accessToken: string | null = null;
  private refreshTask: Promise<MobileAuthenticationState> | null = null;
  private state: MobileAuthenticationState = unauthenticatedState;
  private readonly listeners = new Set<(state: MobileAuthenticationState) => void>();

  constructor(
    private readonly api: MobileAuthApiClient,
    private readonly credentials: SecureCredentialStore,
    private readonly deviceIdentity: DeviceIdentityProvider,
  ) {}

  getAccessToken(): string | null {
    // accessToken 只保留在该进程对象中，绝不写入 SQLite、SecureStore 或日志。
    return this.accessToken;
  }

  getState(): MobileAuthenticationState {
    return this.state;
  }

  subscribe(listener: (state: MobileAuthenticationState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async register(request: RegisterRequest, idempotencyKey: string): Promise<void> {
    await this.api.registerUser(request, idempotencyKey);
  }

  async requestRegistrationChallenge(email: string): Promise<number> {
    const { deviceId } = await this.deviceIdentity.get();
    const response = await this.api.createRegistrationChallenge({ email, deviceId });
    return response.data.expiresIn;
  }

  async signIn(email: string, password: string): Promise<void> {
    const identity = await this.deviceIdentity.get();
    const response = await this.api.createMobileSession({ email, password, ...identity });
    await this.persistAndConfirm(response.data.session, response.data.tokens.accessToken, response.data.tokens.refreshToken);
  }

  async restore(): Promise<MobileAuthenticationState> {
    return this.refresh();
  }

  async refresh(): Promise<MobileAuthenticationState> {
    if (this.refreshTask) return this.refreshTask;

    this.refreshTask = this.refreshInternal().finally(() => {
      this.refreshTask = null;
    });
    return this.refreshTask;
  }

  async signOut(): Promise<LogoutResult> {
    let remoteSessionRevoked = false;
    try {
      if (this.accessToken) {
        await this.api.revokeCurrentSession();
        remoteSessionRevoked = true;
      }
    } catch {
      // 远端不可达时仍继续本地安全退出，不能让过期凭据留在当前设备。
    }

    const localCredentialsCleared = await this.clearLocalCredentials();
    return { localCredentialsCleared, remoteSessionRevoked };
  }

  async invalidateAuthentication(): Promise<boolean> {
    // 认证响应已明确失效时只做本机清理；不能在 403 路径额外发起会话写请求。
    return this.clearLocalCredentials();
  }

  private async refreshInternal(): Promise<MobileAuthenticationState> {
    this.publish({ status: 'RESTORING', session: this.state.session, userId: this.state.userId, errorMessage: null });
    let refreshToken: string | null;
    try {
      refreshToken = await this.credentials.readRefreshCredential();
    } catch {
      // 安全存储被锁定时不能误判退出或消费刷新凭据，保留材料并等待用户解锁后重试。
      this.publish({ status: 'RECOVERABLE_ERROR', session: null, userId: null, errorMessage: '无法读取本机安全凭据，请解锁设备后重试。' });
      return this.state;
    }
    if (!refreshToken) {
      this.clearMemory();
      return this.state;
    }

    try {
      const response = await this.api.refreshMobileSession({ refreshToken });
      await this.persistAndConfirm(response.data.session, response.data.tokens.accessToken, response.data.tokens.refreshToken);
      return this.state;
    } catch (error) {
      if (error instanceof SecureCredentialWriteError) {
        // 新 Token 已轮换但无法安全保存时旧 Token 已不可再用，必须保持明确退出状态。
        return this.state;
      }
      if (isInvalidCredentialError(error) || isForbiddenError(error)) {
        await this.clearLocalCredentials();
        return this.state;
      }

      // 网络和 5xx 不消费本地凭据，用户可在恢复网络后安全重试刷新。
      this.publish({ status: 'RECOVERABLE_ERROR', session: null, userId: null, errorMessage: '网络或服务暂不可用，请稍后重试。' });
      return this.state;
    }
  }

  private async persistAndConfirm(session: Session, accessToken: string, refreshToken: string): Promise<void> {
    try {
      // 刷新 Token 轮换必须先落到系统安全存储，成功后才允许 UI 得到已登录状态。
      await this.credentials.writeRefreshCredential(refreshToken);
    } catch {
      await this.clearLocalCredentials();
      throw new SecureCredentialWriteError();
    }

    this.accessToken = accessToken;
    let userId: string;
    try {
      // 只有服务端 Bearer 返回的 User.id 才是主体事实；此处禁止从 JWT、SecureStore 或 SQLite 推断。
      userId = (await this.api.getCurrentUser()).data.id;
    } catch (error) {
      if (isInvalidCredentialError(error) || isForbiddenError(error)) {
        await this.clearLocalCredentials();
        return;
      }

      this.accessToken = null;
      this.publish({ status: 'RECOVERABLE_ERROR', session: null, userId: null, errorMessage: '网络或服务暂不可用，请稍后重试。' });
      return;
    }

    if (userId.trim().length === 0) {
      this.accessToken = null;
      this.publish({ status: 'RECOVERABLE_ERROR', session: null, userId: null, errorMessage: '服务端未返回有效登录主体，请稍后重试。' });
      return;
    }

    if (this.state.userId !== null && this.state.userId !== userId) {
      // 刷新主体变化时不得把旧用户 scope 带入新会话；本轮新凭据也必须失效闭合。
      await this.clearLocalCredentials();
      return;
    }

    this.publish({ status: 'AUTHENTICATED', session, userId, errorMessage: null });
  }

  private async clearLocalCredentials(): Promise<boolean> {
    // 先关闭内存主体和 SQLite scope，再等待 SecureStore；删除被锁拒绝时也绝不能继续访问旧用户数据。
    this.clearMemory();
    try {
      await this.credentials.clearRefreshCredential();
      return true;
    } catch {
      // 删除失败意味着下次启动仍可能恢复会话，必须显式提示而不是伪装为已安全退出。
      this.publish({ status: 'RECOVERABLE_ERROR', session: null, userId: null, errorMessage: '无法清除本机安全凭据，请解锁设备后重试本机安全退出。' });
      return false;
    }
  }

  private clearMemory(): void {
    this.accessToken = null;
    this.publish(unauthenticatedState);
  }

  private publish(state: MobileAuthenticationState): void {
    this.state = state;
    this.listeners.forEach((listener) => listener(state));
  }
}

function isInvalidCredentialError(error: unknown): boolean {
  return error instanceof ApiClientError && (error.problem.status === 401 || error.problem.code === 'INVALID_CREDENTIALS');
}

function isForbiddenError(error: unknown): boolean {
  return error instanceof ApiClientError && error.problem.status === 403;
}
