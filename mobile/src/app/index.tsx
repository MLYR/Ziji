import { useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';

import { ApiClientError } from '@/api/api-client';
import { mobileAuthenticationSession } from '@/auth/default-auth-session';
import { createRegistrationIdempotencyKey, type MobileAuthenticationState } from '@/auth/auth-session';
import { localBiometricLock, type LocalBiometricCapability, type LocalBiometricOutcome } from '@/auth/local-biometric-lock';
import { useThemeStore } from '@/state/theme-store';
import { SyncStatusPanel } from '@/sync/sync-status-panel';
import { HomeDashboardPanel } from '@/dashboard/home-dashboard-panel';

type AuthMode = 'LOGIN' | 'REGISTER';
type FieldName = 'email' | 'password' | 'verificationCode' | 'nickname';
// 本地生物识别锁门禁状态：CHECKING=启动检查中，LOCKED=凭据受本地锁保护未解锁，UNLOCKED=可正常恢复会话。
type LocalLockStatus = 'CHECKING' | 'LOCKED' | 'UNLOCKED';

function problemMessage(error: unknown): { message: string; fieldErrors: Partial<Record<FieldName, string>> } {
  if (error instanceof Error && error.name === 'SecureCredentialWriteError') {
    return { message: error.message, fieldErrors: {} };
  }
  if (!(error instanceof ApiClientError)) return { message: '网络或服务暂不可用，请稍后重试。', fieldErrors: {} };

  const fieldErrors = Object.fromEntries(
    (error.problem.fieldErrors ?? []).filter((item): item is { field: FieldName; code: string; message?: string | null } =>
      item.field === 'email' || item.field === 'password' || item.field === 'verificationCode' || item.field === 'nickname',
    ).map((item) => [item.field, item.message ?? '请检查此字段。']),
  );
  const rateLimit = error.problem.status === 429
    ? error.retryAfterSeconds === null ? '请求过于频繁，请稍后重试。' : `请求过于频繁，请在 ${error.retryAfterSeconds} 秒后重试。`
    : error.problem.detail ?? error.problem.title;
  return { message: rateLimit, fieldErrors };
}

// 锁定屏的失败提示：所有非 SUCCESS 结果都必须停留在本地锁定状态，不允许恢复会话。
function localLockFailureMessage(outcome: LocalBiometricOutcome): string {
  switch (outcome) {
    case 'UNAVAILABLE': return '当前设备不支持生物识别，无法完成本地解锁，请重新登录后继续。';
    case 'NOT_ENROLLED': return '当前设备未录入生物识别，请录入后重试，或重新登录。';
    case 'CANCELLED': return '已取消生物识别验证，可重试或重新登录。';
    case 'LOCKED_OUT': return '生物识别已被系统暂时锁定，请稍后重试，或重新登录。';
    case 'ERROR': return '生物识别服务异常，请重试或重新登录。';
    default: return '生物识别验证未通过，请重试。';
  }
}

// 启用失败提示：任何失败都不得把本机锁偏好标记为已启用。
function biometricEnableFailureMessage(outcome: LocalBiometricOutcome): string {
  switch (outcome) {
    case 'UNAVAILABLE': return '当前设备不支持生物识别，无法启用本地解锁。';
    case 'NOT_ENROLLED': return '当前设备未录入生物识别，请先在系统设置中录入。';
    case 'CANCELLED': return '已取消生物识别验证，未启用本地解锁。';
    case 'LOCKED_OUT': return '生物识别已被系统暂时锁定，请稍后重试。';
    case 'ERROR': return '生物识别服务异常，请稍后重试。';
    default: return '生物识别验证未通过，未启用本地解锁。';
  }
}

export default function AuthenticationScreen() {
  const router = useRouter();
  const [authentication, setAuthentication] = useState<MobileAuthenticationState>(() => mobileAuthenticationSession.getState());
  const [mode, setMode] = useState<AuthMode>('LOGIN');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const [nickname, setNickname] = useState('');
  const [challengeExpiresIn, setChallengeExpiresIn] = useState<number | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [localLockStatus, setLocalLockStatus] = useState<LocalLockStatus>('CHECKING');
  const [localLockMessage, setLocalLockMessage] = useState<string | null>(null);
  const [biometricEnabled, setBiometricEnabled] = useState(false);
  const [biometricCapability, setBiometricCapability] = useState<LocalBiometricCapability | null>(null);
  const [biometricCapabilityFailed, setBiometricCapabilityFailed] = useState(false);
  const [biometricMessage, setBiometricMessage] = useState<string | null>(null);
  const registrationIdempotencyKey = useRef<string | null>(null);
  // 本地锁代次：重新登录/退出使进行中的解锁结果失效，防止旧 unlock 完成后恢复已失效会话。
  const lockGenerationRef = useRef(0);
  // 冷启动本地锁解除后只允许补发一次现有 restore；前台重复解锁不得重复恢复会话。
  const coldStartRestorePendingRef = useRef(false);
  const requiresUnlockOnReturnRef = useRef(false);
  // AppState 监听挂载一次即不再更新，锁偏好通过 ref 镜像供其读取，避免闭包读到陈旧值。
  const biometricEnabledRef = useRef(false);
  const themePreference = useThemeStore((state) => state.preference);
  const setThemePreference = useThemeStore((state) => state.setPreference);

  useEffect(() => {
    biometricEnabledRef.current = biometricEnabled;
  }, [biometricEnabled]);

  useEffect(() => {
    const unsubscribe = mobileAuthenticationSession.subscribe(setAuthentication);
    let cancelled = false;
    void (async () => {
      let enabled: boolean;
      try {
        enabled = await localBiometricLock.isEnabled();
      } catch {
        // 本机锁设置读取失败时 fail-closed：按已启用处理，不允许直接读取刷新凭据恢复会话。
        enabled = true;
        if (!cancelled) setLocalLockMessage('无法读取本机生物识别设置，可重新登录后继续。');
      }
      if (cancelled) return;
      setBiometricEnabled(enabled);
      if (enabled) {
        // 已启用本地锁：先停在本机解锁界面，验证成功前不得调用 restore 读取并使用刷新凭据。
        coldStartRestorePendingRef.current = true;
        setLocalLockStatus('LOCKED');
        return;
      }
      setLocalLockStatus('UNLOCKED');
      await mobileAuthenticationSession.restore();
    })();
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    if (authentication.status !== 'AUTHENTICATED') return;
    let cancelled = false;
    void (async () => {
      try {
        const capability = await localBiometricLock.checkCapability();
        if (cancelled) return;
        setBiometricCapability(capability);
        setBiometricCapabilityFailed(false);
      } catch {
        if (!cancelled) {
          setBiometricCapability(null);
          setBiometricCapabilityFailed(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [authentication.status]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (nextState) => {
      if (nextState === 'background' || nextState === 'inactive') {
        // 生物识别 prompt 自身会让应用进入 inactive；认证任务在途时不得标记重新锁定，避免递归弹框。
        if (localBiometricLock.isAuthenticating()) return;
        requiresUnlockOnReturnRef.current = true;
        // 进入后台立即遮罩敏感内容，避免应用切换器快照和回前台渲染窗口泄露；只读进程内状态，不碰存储。
        if (biometricEnabledRef.current && mobileAuthenticationSession.getState().status !== 'UNAUTHENTICATED') {
          setLocalLockStatus('LOCKED');
        }
        return;
      }
      if (nextState !== 'active' || !requiresUnlockOnReturnRef.current) return;
      requiresUnlockOnReturnRef.current = false;
      void (async () => {
        // 前台不自动弹原生认证框，只切到本机锁定界面；恢复敏感内容由用户显式点击解锁。
        if (mobileAuthenticationSession.getState().status === 'UNAUTHENTICATED') return;
        let enabled: boolean;
        try {
          enabled = await localBiometricLock.isEnabled();
        } catch {
          enabled = true;
        }
        if (!enabled) return;
        setLocalLockMessage(null);
        setLocalLockStatus('LOCKED');
      })();
    });
    return () => subscription.remove();
  }, []);

  function updateField(field: FieldName, value: string): void {
    registrationIdempotencyKey.current = null;
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
    setMessage(null);
    if (field === 'email') setEmail(value);
    if (field === 'password') setPassword(value);
    if (field === 'verificationCode') setVerificationCode(value);
    if (field === 'nickname') setNickname(value);
  }

  async function submit(): Promise<void> {
    setIsSubmitting(true);
    setMessage(null);
    setFieldErrors({});
    try {
      if (mode === 'LOGIN') {
        await mobileAuthenticationSession.signIn(email, password);
        try {
          // 新登录主体不得继承上一主体的本机锁偏好，需要在新会话下重新主动启用。
          await localBiometricLock.disable();
          setBiometricEnabled(false);
        } catch {
          // 清除失败方向安全：下次启动仍会要求本地解锁，不会降低保护。
        }
        return;
      }

      if (challengeExpiresIn === null) {
        const expiresIn = await mobileAuthenticationSession.requestRegistrationChallenge(email);
        setChallengeExpiresIn(expiresIn);
        setMessage(`验证码已发送，请在 ${Math.ceil(expiresIn / 60)} 分钟内完成注册。`);
        return;
      }

      registrationIdempotencyKey.current ??= createRegistrationIdempotencyKey();
      await mobileAuthenticationSession.register(
        { email, password, verificationCode, nickname, timezone: 'Asia/Shanghai', baseCurrency: 'CNY', locale: 'zh-CN' },
        registrationIdempotencyKey.current,
      );
      registrationIdempotencyKey.current = null;
      try {
        // 注册意味着新主体将接管设备，旧主体的本机锁偏好不得继续残留。
        await localBiometricLock.disable();
        setBiometricEnabled(false);
      } catch {
        // 清除失败方向安全：下次启动仍会要求本地解锁，不会降低保护。
      }
      setMessage('注册成功，请使用邮箱和密码登录。');
      setMode('LOGIN');
      setVerificationCode('');
    } catch (error) {
      const result = problemMessage(error);
      setMessage(result.message);
      setFieldErrors(result.fieldErrors);
    } finally {
      setIsSubmitting(false);
    }
  }

  async function signOut(): Promise<void> {
    // 使进行中的解锁结果失效，防止旧 unlock 完成后恢复已失效会话。
    lockGenerationRef.current += 1;
    // 退出后不再存在“冷启动待补发的恢复”，新主体登录前不得复用旧门禁状态。
    coldStartRestorePendingRef.current = false;
    setIsSubmitting(true);
    try {
      const result = await mobileAuthenticationSession.signOut();
      if (result.localCredentialsCleared) {
        try {
          // 本机凭据已清除时锁偏好一并清除；凭据清除失败则保留本地锁，继续保护遗留凭据。
          await localBiometricLock.disable();
          setBiometricEnabled(false);
        } catch {
          // 锁偏好清除失败仅影响下次启动的本地解锁提示，方向安全。
        }
      }
      setLocalLockStatus('UNLOCKED');
      setLocalLockMessage(null);
      setBiometricMessage(null);
      setMessage(result.localCredentialsCleared
        ? result.remoteSessionRevoked ? '已退出当前设备。' : '已安全退出本机；服务端会话将在恢复网络后失效。'
        : '本机安全凭据未能清除，请解锁设备后重试。');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function unlockLocalLock(): Promise<void> {
    const generation = lockGenerationRef.current;
    setIsSubmitting(true);
    setLocalLockMessage(null);
    try {
      const outcome = await localBiometricLock.unlock();
      if (generation !== lockGenerationRef.current) return;
      if (outcome !== 'SUCCESS') {
        setLocalLockMessage(localLockFailureMessage(outcome));
        return;
      }
      setLocalLockMessage(null);
      setLocalLockStatus('UNLOCKED');
      if (coldStartRestorePendingRef.current) {
        // 本地解锁成功后才允许读取刷新凭据恢复会话，且整个冷启动只补发一次 restore。
        coldStartRestorePendingRef.current = false;
        await mobileAuthenticationSession.restore();
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  async function enableBiometric(): Promise<void> {
    setIsSubmitting(true);
    setBiometricMessage(null);
    try {
      const outcome = await localBiometricLock.enable();
      if (outcome !== 'SUCCESS') {
        setBiometricMessage(biometricEnableFailureMessage(outcome));
        return;
      }
      setBiometricEnabled(true);
    } finally {
      setIsSubmitting(false);
    }
  }

  async function disableBiometric(): Promise<void> {
    setIsSubmitting(true);
    setBiometricMessage(null);
    try {
      // 关闭本地锁前要求一次生物识别验证；设备已不可用或未录入时没有可验证对象，直接允许关闭。
      const outcome = await localBiometricLock.unlock();
      if (outcome !== 'SUCCESS' && outcome !== 'UNAVAILABLE' && outcome !== 'NOT_ENROLLED') {
        setBiometricMessage('关闭生物识别解锁前需要先完成本地验证。');
        return;
      }
      await localBiometricLock.disable();
      setBiometricEnabled(false);
    } catch {
      setBiometricMessage('无法更新本机生物识别设置，请解锁设备后重试。');
    } finally {
      setIsSubmitting(false);
    }
  }

  const isRestoring = authentication.status === 'RESTORING';
  const isAuthenticated = authentication.status === 'AUTHENTICATED';
  const submitLabel = mode === 'LOGIN' ? '登录' : challengeExpiresIn === null ? '发送验证码' : '完成注册';

  return (
    <SafeAreaView className="flex-1 bg-canvas-light dark:bg-canvas-dark" edges={['top', 'bottom']}>
      <ScrollView className="flex-1" contentContainerClassName="min-h-full justify-between px-7 py-6" keyboardShouldPersistTaps="handled">
        <View>
          <Pressable
            // iOS 会将可访问按钮内的 Text 合并到父节点，标签直接暴露当前主题状态。
            accessibilityHint="点击切换深浅主题"
            accessibilityLabel={`主题：${themePreference === 'system' ? '系统' : themePreference === 'light' ? '浅色' : '深色'}`}
            accessibilityRole="button"
            className="min-h-11 self-end justify-center px-2 active:opacity-70"
            onPress={() => setThemePreference(themePreference === 'system' ? 'light' : themePreference === 'light' ? 'dark' : 'system')}
            testID="theme-toggle"
          >
            <Text className="text-sm font-semibold text-muted-light dark:text-muted-dark">主题：{themePreference === 'system' ? '系统' : themePreference === 'light' ? '浅色' : '深色'}</Text>
          </Pressable>
          <View className="h-11 w-11 items-center justify-center rounded-xl bg-accent">
            <Text className="text-lg font-extrabold text-canvas-dark">Z</Text>
          </View>
          <Text className="mt-4 text-sm font-semibold tracking-wide text-muted-light dark:text-muted-dark">资迹 ZIJI</Text>
          <Text className="mt-4 text-4xl font-bold leading-tight text-ink-light dark:text-ink-dark" accessibilityRole="header">
            看清每一笔钱{`\n`}现在在哪里。
          </Text>
          <Text className="mt-3 text-base leading-6 text-muted-light dark:text-muted-dark">登录后继续查看资金全貌。</Text>
        </View>

        {localLockStatus === 'CHECKING' ? (
          // 本机锁状态确认前只显示中性占位，既不能直通已认证面板，也不闪烁登录表单。
          <View className="my-10" accessibilityLiveRegion="polite">
            <Text className="text-base text-muted-light dark:text-muted-dark">正在准备安全环境…</Text>
          </View>
        ) : localLockStatus === 'LOCKED' ? (
          <View className="my-10 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" accessibilityLiveRegion="polite">
            <Text className="text-xl font-bold text-ink-light dark:text-ink-dark">资迹已锁定</Text>
            <Text className="mt-2 text-base text-muted-light dark:text-muted-dark">使用生物识别验证后才能继续查看内容。</Text>
            {localLockMessage ? <Text className="mt-3 text-sm leading-5 text-muted-light dark:text-muted-dark" accessibilityRole="alert">{localLockMessage}</Text> : null}
            <Pressable
              accessibilityLabel="使用生物识别解锁"
              accessibilityRole="button"
              accessibilityState={{ disabled: isSubmitting }}
              className={`mt-5 min-h-11 items-center justify-center rounded-lg bg-accent ${isSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
              disabled={isSubmitting}
              onPress={() => void unlockLocalLock()}
              testID="biometric-unlock"
            >
              <Text className="font-bold text-canvas-dark">使用生物识别解锁</Text>
            </Pressable>
            <Pressable
              accessibilityLabel="重新登录"
              accessibilityRole="button"
              // 原生认证框在途时也必须可回退重新登录；并发安全由本地锁代次与 signOut 内部保证。
              className="mt-3 min-h-11 items-center justify-center rounded-lg border border-accent active:opacity-70"
              onPress={() => void signOut()}
              testID="biometric-relogin"
            >
              <Text className="font-semibold text-ink-light dark:text-ink-dark">重新登录</Text>
            </Pressable>
          </View>
        ) : isAuthenticated ? (
          <View className="my-10 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" accessibilityLiveRegion="polite">
            <Text className="text-xl font-bold text-ink-light dark:text-ink-dark">已安全登录</Text>
            <Text className="mt-2 text-base text-muted-light dark:text-muted-dark">当前设备：{authentication.session?.deviceName}</Text>
            <View className="mt-4 flex-row gap-2">
              <Pressable
                accessibilityLabel="快速记账"
                accessibilityRole="button"
                className="min-h-11 flex-1 items-center justify-center rounded-lg bg-accent active:opacity-70"
                onPress={() => router.push('/quick-record')}
                testID="open-quick-record"
              >
                <Text className="font-bold text-canvas-dark">快速记账</Text>
              </Pressable>
              <Pressable
                accessibilityLabel="查看账户"
                accessibilityRole="button"
                className="min-h-11 flex-1 items-center justify-center rounded-lg border border-accent active:opacity-70"
                onPress={() => router.push('/accounts')}
                testID="open-accounts"
              >
                <Text className="font-semibold text-ink-light dark:text-ink-dark">账户</Text>
              </Pressable>
              <Pressable
                accessibilityLabel="查看投资"
                accessibilityRole="button"
                className="min-h-11 flex-1 items-center justify-center rounded-lg border border-accent active:opacity-70"
                // 新增文件路由的本地 typed-routes 缓存尚未包含该路径，运行时仍由 Expo Router 按文件路由解析。
                onPress={() => router.push('/investments' as never)}
                testID="open-investments"
              >
                <Text className="font-semibold text-ink-light dark:text-ink-dark">投资</Text>
              </Pressable>
            </View>
            <HomeDashboardPanel onOpenQuickRecord={() => router.push('/quick-record')} />
            {authentication.userId ? <SyncStatusPanel userId={authentication.userId} /> : null}
            <View className="mt-5 border-t border-canvas-light pt-4 dark:border-canvas-dark">
              <Text className="text-base font-semibold text-ink-light dark:text-ink-dark">本机生物识别解锁</Text>
              {biometricEnabled ? (
                <>
                  <Text className="mt-2 text-sm text-muted-light dark:text-muted-dark">生物识别解锁已开启，下次进入应用需要先完成本地验证。</Text>
                  <Pressable
                    accessibilityLabel="关闭生物识别解锁"
                    accessibilityRole="button"
                    accessibilityState={{ disabled: isSubmitting }}
                    className={`mt-3 min-h-11 items-center justify-center rounded-lg border border-accent ${isSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
                    disabled={isSubmitting}
                    onPress={() => void disableBiometric()}
                    testID="biometric-disable"
                  >
                    <Text className="font-semibold text-ink-light dark:text-ink-dark">关闭生物识别解锁</Text>
                  </Pressable>
                </>
              ) : biometricCapability?.hasHardware && biometricCapability.isEnrolled ? (
                <Pressable
                  accessibilityLabel="启用生物识别解锁"
                  accessibilityRole="button"
                  accessibilityState={{ disabled: isSubmitting }}
                  className={`mt-3 min-h-11 items-center justify-center rounded-lg border border-accent ${isSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
                  disabled={isSubmitting}
                  onPress={() => void enableBiometric()}
                  testID="biometric-enable"
                >
                  <Text className="font-semibold text-ink-light dark:text-ink-dark">启用生物识别解锁</Text>
                </Pressable>
              ) : (
                <Text className="mt-2 text-sm text-muted-light dark:text-muted-dark">
                  {biometricCapabilityFailed
                    ? '暂时无法检测本机生物识别能力，请稍后重试。'
                    : biometricCapability === null
                      ? '正在检测本机生物识别能力…'
                      : !biometricCapability.hasHardware
                        ? '当前设备不支持生物识别，无法启用本地解锁。'
                        : '当前设备未录入生物识别，请先在系统设置中录入后再启用。'}
                </Text>
              )}
              {biometricMessage ? <Text className="mt-3 text-sm leading-5 text-muted-light dark:text-muted-dark" accessibilityRole="alert">{biometricMessage}</Text> : null}
            </View>
            <Pressable
              accessibilityLabel="退出当前设备"
              accessibilityRole="button"
              accessibilityState={{ disabled: isSubmitting }}
              className={`mt-6 min-h-11 items-center justify-center rounded-lg border border-accent ${isSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
              disabled={isSubmitting}
              onPress={() => void signOut()}
            >
              <Text className="font-semibold text-ink-light dark:text-ink-dark">退出当前设备</Text>
            </Pressable>
          </View>
        ) : (
          <View className="my-9" accessibilityLiveRegion="polite">
            <View className="flex-row rounded-lg bg-surface-light p-1 dark:bg-surface-dark">
              {(['LOGIN', 'REGISTER'] as const).map((nextMode) => (
                <Pressable
                  key={nextMode}
                  accessibilityLabel={nextMode === 'LOGIN' ? '切换到登录' : '切换到注册'}
                  accessibilityRole="tab"
                  accessibilityState={{ selected: mode === nextMode, disabled: isSubmitting || isRestoring }}
                  className={`min-h-11 flex-1 items-center justify-center rounded-md ${mode === nextMode ? 'bg-accent' : 'active:opacity-70'}`}
                  disabled={isSubmitting || isRestoring}
                  onPress={() => {
                    // 切换页签即放弃当前注册载荷，不能把旧幂等键带入不同请求。
                    registrationIdempotencyKey.current = null;
                    setChallengeExpiresIn(null);
                    setMode(nextMode);
                    setMessage(null);
                    setFieldErrors({});
                  }}
                >
                  <Text className="font-semibold text-ink-light dark:text-ink-dark">{nextMode === 'LOGIN' ? '登录' : '注册'}</Text>
                </Pressable>
              ))}
            </View>

            <Field label="邮箱地址" error={fieldErrors.email}>
              <TextInput accessibilityLabel="邮箱地址" autoCapitalize="none" autoComplete="email" className="min-h-11 rounded-lg bg-surface-light px-3 text-base text-ink-light dark:bg-surface-dark dark:text-ink-dark" editable={!isSubmitting && !isRestoring} inputMode="email" onChangeText={(value) => updateField('email', value)} value={email} />
            </Field>
            <Field label="密码" error={fieldErrors.password}>
              <TextInput accessibilityLabel="密码" autoComplete={mode === 'LOGIN' ? 'current-password' : 'new-password'} className="min-h-11 rounded-lg bg-surface-light px-3 text-base text-ink-light dark:bg-surface-dark dark:text-ink-dark" editable={!isSubmitting && !isRestoring} onChangeText={(value) => updateField('password', value)} secureTextEntry value={password} />
            </Field>
            {mode === 'REGISTER' && challengeExpiresIn !== null ? (
              <>
                <Field label="邮箱验证码" error={fieldErrors.verificationCode}>
                  <TextInput accessibilityLabel="邮箱验证码" className="min-h-11 rounded-lg bg-surface-light px-3 text-base text-ink-light dark:bg-surface-dark dark:text-ink-dark" editable={!isSubmitting} inputMode="numeric" onChangeText={(value) => updateField('verificationCode', value)} value={verificationCode} />
                </Field>
                <Field label="昵称" error={fieldErrors.nickname}>
                  <TextInput accessibilityLabel="昵称" className="min-h-11 rounded-lg bg-surface-light px-3 text-base text-ink-light dark:bg-surface-dark dark:text-ink-dark" editable={!isSubmitting} onChangeText={(value) => updateField('nickname', value)} value={nickname} />
                </Field>
              </>
            ) : null}

            {(message ?? authentication.errorMessage) ? <Text className="mt-3 text-sm leading-5 text-muted-light dark:text-muted-dark" accessibilityRole="alert">{message ?? authentication.errorMessage}</Text> : null}
            <Pressable
              accessibilityLabel={isSubmitting || isRestoring ? '正在提交认证信息' : submitLabel}
              accessibilityRole="button"
              accessibilityState={{ busy: isSubmitting || isRestoring, disabled: isSubmitting || isRestoring }}
              className={`mt-5 min-h-11 items-center justify-center rounded-lg bg-accent ${(isSubmitting || isRestoring) ? 'opacity-50' : 'active:opacity-70'}`}
              disabled={isSubmitting || isRestoring}
              onPress={() => void submit()}
              testID="auth-submit"
            >
              <Text className="font-bold text-canvas-dark">{isSubmitting || isRestoring ? '请稍候…' : submitLabel}</Text>
            </Pressable>
            {authentication.status === 'RECOVERABLE_ERROR' ? (
              <Pressable
                accessibilityLabel="本机安全退出"
                accessibilityRole="button"
                accessibilityState={{ disabled: isSubmitting }}
                className={`mt-3 min-h-11 items-center justify-center rounded-lg border border-accent ${isSubmitting ? 'opacity-50' : 'active:opacity-70'}`}
                disabled={isSubmitting}
                onPress={() => void signOut()}
              >
                <Text className="font-semibold text-ink-light dark:text-ink-dark">本机安全退出</Text>
              </Pressable>
            ) : null}
          </View>
        )}

        <Text className="text-center text-sm leading-5 text-muted-light dark:text-muted-dark">仅支持邮箱登录；V1 不支持微信或手机号登录。</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function Field({ children, error, label }: { children: ReactNode; error?: string; label: string }) {
  return (
    <View className="mt-4">
      <Text className="mb-2 text-sm font-semibold text-ink-light dark:text-ink-dark">{label}</Text>
      {children}
      {error ? <Text className="mt-1 text-sm text-ink-light dark:text-ink-dark" accessibilityRole="alert">{`错误：${error}`}</Text> : null}
    </View>
  );
}
