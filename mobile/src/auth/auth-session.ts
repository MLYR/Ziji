import type { components } from '@ziji/api-types';

import { ApiClientError, type MobileAuthApiClient, type RegisterRequest } from '@/api/api-client';
import type { SecureCredentialStore } from '@/storage/secure-credentials';

type Session = components['schemas']['Session'];
type MobileSessionEnvelope = components['schemas']['MobileSessionEnvelope'];
type UserEnvelope = components['schemas']['UserEnvelope'];

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

export type LocalScopeInvalidator = () => Promise<void>;

export class AuthenticationScopeExpiredError extends Error {
  constructor() {
    super('认证 scope 已失效。');
    this.name = 'AuthenticationScopeExpiredError';
  }
}

export interface MobileAuthenticationScopeLease {
  readonly userId: string;
  readonly generation: number;
  readonly accessToken: string;
  isCurrent(): boolean;
  assertCurrent(): void;
  withOperation<T>(operation: () => Promise<T>): Promise<T>;
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
  private sessionGeneration = 0;
  private signInGeneration: number | null = null;
  private credentialTask: Promise<void> = Promise.resolve();
  private scopeCloseTask: Promise<boolean> = Promise.resolve(true);
  private scopeCloseGeneration: number | null = null;
  private readonly activeScopeOperations = new Map<number, Set<Promise<void>>>();
  private readonly listeners = new Set<(state: MobileAuthenticationState) => void>();

  constructor(
    private readonly api: MobileAuthApiClient,
    private readonly credentials: SecureCredentialStore,
    private readonly deviceIdentity: DeviceIdentityProvider,
    private readonly invalidateLocalScope: LocalScopeInvalidator = async () => undefined,
  ) {}

  getAccessToken(): string | null {
    // accessToken 只保留在该进程对象中，绝不写入 SQLite、SecureStore 或日志。
    return this.accessToken;
  }

  getState(): MobileAuthenticationState {
    return this.state;
  }

  getCurrentScopeLease(): MobileAuthenticationScopeLease | null {
    if (this.signInGeneration !== null || this.state.status !== 'AUTHENTICATED' || this.state.userId === null || this.accessToken === null) return null;

    const userId = this.state.userId;
    const generation = this.sessionGeneration;
    const accessToken = this.accessToken;
    return {
      userId,
      generation,
      accessToken,
      isCurrent: () => this.isCurrentScopeLease(generation, userId, accessToken),
      assertCurrent: () => {
        if (!this.isCurrentScopeLease(generation, userId, accessToken)) throw new AuthenticationScopeExpiredError();
      },
      withOperation: <T>(operation: () => Promise<T>) => this.withScopeOperation(generation, userId, accessToken, operation),
    };
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
    const previousGeneration = this.sessionGeneration;
    const generation = ++this.sessionGeneration;
    this.signInGeneration = generation;
    // 登录切换同样必须先撤销旧主体并排空旧代次的 SQLite 操作，再允许新主体完成确认。
    const shouldClosePreviousScope = this.state.status === 'AUTHENTICATED'
      || this.accessToken !== null
      || this.activeScopeOperations.has(previousGeneration)
      || this.scopeCloseGeneration === previousGeneration;
    this.clearMemory();
    if (shouldClosePreviousScope) this.startScopeClose(previousGeneration);
    try {
      const identity = await this.deviceIdentity.get();
      const response = await this.api.createMobileSession({ email, password, ...identity });
      if (!isValidMobileSessionEnvelope(response)) {
        await this.publishRecoverableAuthenticationError(generation, '服务端认证响应无效，请稍后重试。');
        return;
      }
      await this.persistAndConfirm(response.data.session, response.data.tokens.accessToken, response.data.tokens.refreshToken, generation);
    } finally {
      if (this.signInGeneration === generation) this.signInGeneration = null;
    }
  }

  async restore(): Promise<MobileAuthenticationState> {
    return this.refresh();
  }

  async refresh(): Promise<MobileAuthenticationState> {
    // 用户主动登录优先于并发恢复；自动 refresh 不得覆盖尚未完成的新登录响应。
    if (this.signInGeneration !== null) return this.state;
    if (this.refreshTask) return this.refreshTask;

    const generation = this.sessionGeneration;
    this.refreshTask = this.refreshInternal(generation).finally(() => {
      this.refreshTask = null;
    });
    return this.refreshTask;
  }

  async signOut(): Promise<LogoutResult> {
    const generation = this.sessionGeneration;
    let remoteSessionRevoked = false;
    try {
      if (this.accessToken && this.isCurrentGeneration(generation)) {
        await this.api.revokeCurrentSession();
        remoteSessionRevoked = true;
      }
    } catch {
      // 远端不可达时仍继续本地安全退出，不能让过期凭据留在当前设备。
    }

    // 另一轮登录或认证失效已经接管当前会话时，旧退出请求不得清除新主体的本地材料。
    if (!this.isCurrentGeneration(generation)) {
      return { localCredentialsCleared: true, remoteSessionRevoked };
    }
    const localCredentialsCleared = await this.clearLocalCredentials();
    return { localCredentialsCleared, remoteSessionRevoked };
  }

  async invalidateAuthentication(expectedLease?: MobileAuthenticationScopeLease): Promise<boolean> {
    // 认证响应已明确失效时只做本机清理；不能在 403 路径额外发起会话写请求。
    return this.clearLocalCredentials(expectedLease);
  }

  private async refreshInternal(generation: number): Promise<MobileAuthenticationState> {
    if (!this.isCurrentGeneration(generation)) return this.state;
    this.publish({ status: 'RESTORING', session: this.state.session, userId: this.state.userId, errorMessage: null });
    let refreshToken: string | null;
    try {
      refreshToken = await this.credentials.readRefreshCredential();
    } catch {
      if (!this.isCurrentGeneration(generation)) return this.state;
      // 安全存储被锁定时不能误判退出或消费刷新凭据，保留材料并等待用户解锁后重试。
      await this.publishRecoverableAuthenticationError(generation, '无法读取本机安全凭据，请解锁设备后重试。');
      return this.state;
    }
    if (!refreshToken) {
      if (!this.isCurrentGeneration(generation)) return this.state;
      // 已确认主体却缺少刷新凭据时不能只清内存，必须连同 SQLite scope 一起失效。
      await this.clearLocalCredentials();
      return this.state;
    }

    try {
      const response = await this.api.refreshMobileSession({ refreshToken });
      if (!isValidMobileSessionEnvelope(response)) {
        await this.publishRecoverableAuthenticationError(generation, '服务端认证响应无效，请稍后重试。');
        return this.state;
      }
      await this.persistAndConfirm(response.data.session, response.data.tokens.accessToken, response.data.tokens.refreshToken, generation);
      return this.state;
    } catch (error) {
      if (!this.isCurrentGeneration(generation)) return this.state;
      if (error instanceof SecureCredentialWriteError) {
        // 新 Token 已轮换但无法安全保存时旧 Token 已不可再用，必须保持明确退出状态。
        return this.state;
      }
      if (isInvalidCredentialError(error) || isForbiddenError(error)) {
        await this.clearLocalCredentials();
        return this.state;
      }

      // 网络和 5xx 不消费本地凭据，用户可在恢复网络后安全重试刷新。
      await this.publishRecoverableAuthenticationError(generation, '网络或服务暂不可用，请稍后重试。');
      return this.state;
    }
  }

  private async persistAndConfirm(session: Session, accessToken: string, refreshToken: string, generation: number): Promise<void> {
    if (!this.isCurrentGeneration(generation)) return;

    let scopeCleared = true;
    try {
      // 新登录必须等上一轮失效完成关闭；否则新主体可能在旧 SQLite 句柄仍存活时发布 AUTHENTICATED。
      scopeCleared = await this.scopeCloseTask;
    } catch {
      scopeCleared = false;
    }
    if (!scopeCleared) {
      await this.publishRecoverableAuthenticationError(generation, '本机同步范围无法安全关闭，请稍后重试。');
      return;
    }

    try {
      // 刷新 Token 轮换必须先落到系统安全存储，成功后才允许 UI 得到已登录状态。
      const persisted = await this.enqueueCredentialOperation(async () => {
        if (!this.isCurrentGeneration(generation)) return false;
        await this.credentials.writeRefreshCredential(refreshToken);
        // 失效可能与 SecureStore 写入并发发生；失效路径已排在同一串行队列中，不能继续确认主体。
        return this.isCurrentGeneration(generation);
      });
      if (!persisted) return;
    } catch {
      if (!this.isCurrentGeneration(generation)) return;
      await this.clearLocalCredentials();
      throw new SecureCredentialWriteError();
    }

    if (!this.isCurrentGeneration(generation)) return;
    this.accessToken = accessToken;
    let userId: unknown;
    try {
      // 只有服务端 Bearer 返回的 User.id 才是主体事实；此处禁止从 JWT、SecureStore 或 SQLite 推断。
      const response = await this.api.getCurrentUser();
      if (!isValidUserEnvelope(response)) {
        await this.publishRecoverableAuthenticationError(generation, '服务端登录主体响应无效，请稍后重试。');
        return;
      }
      userId = response.data.id;
    } catch (error) {
      if (!this.isCurrentGeneration(generation)) return;
      if (isInvalidCredentialError(error) || isForbiddenError(error)) {
        await this.clearLocalCredentials();
        return;
      }

      await this.publishRecoverableAuthenticationError(generation, '网络或服务暂不可用，请稍后重试。');
      return;
    }

    if (!this.isCurrentGeneration(generation)) {
      return;
    }

    if (typeof userId !== 'string' || userId.trim().length === 0) {
      await this.publishRecoverableAuthenticationError(generation, '服务端未返回有效登录主体，请稍后重试。');
      return;
    }

    if (this.state.userId !== null && this.state.userId !== userId) {
      // 刷新主体变化时不得把旧用户 scope 带入新会话；本轮新凭据也必须失效闭合。
      await this.clearLocalCredentials();
      return;
    }

    this.publish({ status: 'AUTHENTICATED', session, userId, errorMessage: null });
  }

  private async publishRecoverableAuthenticationError(generation: number, errorMessage: string): Promise<void> {
    if (!this.isCurrentGeneration(generation)) return;
    // 服务端已无法确认主体时先撤销代次、等待活动 SQLite 操作并关闭 scope，只保留 refresh credential 供稍后恢复。
    const invalidation = await this.invalidateCurrentScope(generation);
    if (!invalidation.didInvalidate || !this.isCurrentGeneration(invalidation.generation)) return;
    this.publish({
      status: 'RECOVERABLE_ERROR',
      session: null,
      userId: null,
      errorMessage: invalidation.scopeCleared ? errorMessage : '本机同步范围无法安全关闭，请稍后重试。',
    });
  }

  private async clearLocalCredentials(expected?: number | MobileAuthenticationScopeLease): Promise<boolean> {
    // 先递增会话代次、等待活动 scope 操作并关闭内存主体；旧请求不能清除代次或 Token 已变化的新会话。
    const invalidation = await this.invalidateCurrentScope(expected);
    if (!invalidation.didInvalidate) return true;
    const generation = invalidation.generation;

    try {
      await this.enqueueCredentialOperation(async () => {
        // 新登录已经开始时，旧失效请求只能让出清理权，不能擦除新一轮凭据。
        if (!this.isCurrentGeneration(generation)) return;
        await this.credentials.clearRefreshCredential();
      });
      if (!this.isCurrentGeneration(generation)) return true;
      if (invalidation.scopeCleared) return true;
    } catch {
      // 删除失败意味着下次启动仍可能恢复会话，必须显式提示而不是伪装为已安全退出。
      if (!this.isCurrentGeneration(generation)) return true;
    }

    if (!this.isCurrentGeneration(generation)) return true;
    this.publish({
      status: 'RECOVERABLE_ERROR',
      session: null,
      userId: null,
      errorMessage: invalidation.scopeCleared ? '无法清除本机安全凭据，请解锁设备后重试本机安全退出。' : '本机同步范围无法安全关闭，请稍后重试。',
    });
    return false;
  }

  private async invalidateCurrentScope(expected?: number | MobileAuthenticationScopeLease): Promise<{ didInvalidate: boolean; generation: number; scopeCleared: boolean }> {
    if (typeof expected === 'number' && !this.isCurrentGeneration(expected)) {
      return { didInvalidate: false, generation: this.sessionGeneration, scopeCleared: true };
    }
    if (expected !== undefined && typeof expected !== 'number' && !this.isCurrentScopeLease(expected.generation, expected.userId, expected.accessToken)) {
      return { didInvalidate: false, generation: this.sessionGeneration, scopeCleared: true };
    }

    const invalidatedGeneration = this.sessionGeneration;
    const nextGeneration = invalidatedGeneration + 1;
    this.sessionGeneration = nextGeneration;
    this.clearMemory();
    const closeTask = this.startScopeClose(invalidatedGeneration);
    const scopeCleared = await closeTask;
    return { didInvalidate: true, generation: nextGeneration, scopeCleared };
  }

  private startScopeClose(invalidatedGeneration: number): Promise<boolean> {
    if (this.scopeCloseGeneration === invalidatedGeneration) return this.scopeCloseTask;

    const previousClose = this.scopeCloseTask.catch(() => false);
    const closeTask = previousClose.then(async () => {
      await this.waitForScopeOperations(invalidatedGeneration);
      try {
        await this.invalidateLocalScope();
        return true;
      } catch {
        return false;
      }
    });
    this.scopeCloseGeneration = invalidatedGeneration;
    this.scopeCloseTask = closeTask;
    return closeTask;
  }

  private isCurrentGeneration(generation: number): boolean {
    return generation === this.sessionGeneration;
  }

  private isCurrentScopeLease(generation: number, userId: string, accessToken: string): boolean {
    return this.signInGeneration === null
      && this.isCurrentGeneration(generation)
      && this.state.status === 'AUTHENTICATED'
      && this.state.userId === userId
      && this.accessToken === accessToken;
  }

  private async withScopeOperation<T>(generation: number, userId: string, accessToken: string, operation: () => Promise<T>): Promise<T> {
    if (!this.isCurrentScopeLease(generation, userId, accessToken)) throw new AuthenticationScopeExpiredError();

    let resolveOperation: () => void = () => undefined;
    const completion = new Promise<void>((resolve) => { resolveOperation = resolve; });
    const operations = this.activeScopeOperations.get(generation) ?? new Set<Promise<void>>();
    operations.add(completion);
    this.activeScopeOperations.set(generation, operations);
    try {
      if (!this.isCurrentScopeLease(generation, userId, accessToken)) throw new AuthenticationScopeExpiredError();
      return await operation();
    } finally {
      operations.delete(completion);
      if (operations.size === 0) this.activeScopeOperations.delete(generation);
      resolveOperation();
    }
  }

  private async waitForScopeOperations(generation: number): Promise<void> {
    while (true) {
      const operations = this.activeScopeOperations.get(generation);
      if (!operations || operations.size === 0) return;
      await Promise.all([...operations]);
    }
  }

  private enqueueCredentialOperation<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.credentialTask;
    const next = previous.catch(() => undefined).then(operation);
    this.credentialTask = next.then(() => undefined, () => undefined);
    return next;
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

function isValidMobileSessionEnvelope(value: unknown): value is MobileSessionEnvelope {
  if (!isRecord(value) || !hasOnlyKeys(value, ['data', 'meta']) || !isResponseMeta(value.meta) || !isRecord(value.data)
    || !hasOnlyKeys(value.data, ['session', 'tokens']) || !isValidSession(value.data.session) || !isValidTokenPair(value.data.tokens)) return false;
  return true;
}

function isValidUserEnvelope(value: unknown): value is UserEnvelope {
  if (!isRecord(value) || !hasOnlyKeys(value, ['data', 'meta']) || !isResponseMeta(value.meta) || !isRecord(value.data)
    || !hasOnlyKeys(value.data, ['id', 'email', 'nickname', 'timezone', 'baseCurrency', 'locale', 'amountFormat', 'status', 'version'])) return false;
  return isUuid(value.data.id)
    && typeof value.data.email === 'string'
    && typeof value.data.nickname === 'string'
    && typeof value.data.timezone === 'string'
    && typeof value.data.baseCurrency === 'string' && /^[A-Z]{3}$/.test(value.data.baseCurrency)
    && typeof value.data.locale === 'string'
    && (value.data.amountFormat === 'STANDARD' || value.data.amountFormat === 'ACCOUNTING')
    && (value.data.status === 'ACTIVE' || value.data.status === 'LOCKED' || value.data.status === 'CLOSING' || value.data.status === 'CLOSED')
    && isPositiveInteger(value.data.version);
}

function isValidSession(value: unknown): boolean {
  return isRecord(value)
    && hasOnlyKeys(value, ['id', 'deviceName', 'deviceId', 'createdAt', 'lastSeenAt', 'status'])
    && isNonEmptyString(value.id)
    && isNonEmptyString(value.deviceName)
    && (value.deviceId === undefined || value.deviceId === null || typeof value.deviceId === 'string')
    && isNonEmptyString(value.createdAt)
    && isNonEmptyString(value.lastSeenAt)
    && (value.status === 'ACTIVE' || value.status === 'REVOKED' || value.status === 'EXPIRED');
}

function isValidTokenPair(value: unknown): boolean {
  return isRecord(value)
    && hasOnlyKeys(value, ['accessToken', 'refreshToken', 'expiresIn'])
    && isNonEmptyString(value.accessToken)
    && isNonEmptyString(value.refreshToken)
    && isPositiveInteger(value.expiresIn);
}

function isResponseMeta(value: unknown): boolean {
  return isRecord(value) && hasOnlyKeys(value, ['requestId']) && isNonEmptyString(value.requestId);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 1;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function hasOnlyKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const allowed = new Set(keys);
  return Object.keys(value).every((key) => allowed.has(key));
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}
