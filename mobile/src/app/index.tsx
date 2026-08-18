import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiClientError } from '@/api/api-client';
import { mobileAuthenticationSession } from '@/auth/default-auth-session';
import { createRegistrationIdempotencyKey, type MobileAuthenticationState } from '@/auth/auth-session';
import { useThemeStore } from '@/state/theme-store';
import { SyncStatusPanel } from '@/sync/sync-status-panel';

type AuthMode = 'LOGIN' | 'REGISTER';
type FieldName = 'email' | 'password' | 'verificationCode' | 'nickname';

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

export default function AuthenticationScreen() {
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
  const registrationIdempotencyKey = useRef<string | null>(null);
  const themePreference = useThemeStore((state) => state.preference);
  const setThemePreference = useThemeStore((state) => state.setPreference);

  useEffect(() => {
    const unsubscribe = mobileAuthenticationSession.subscribe(setAuthentication);
    void mobileAuthenticationSession.restore();
    return unsubscribe;
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
    setIsSubmitting(true);
    try {
      const result = await mobileAuthenticationSession.signOut();
      setMessage(result.localCredentialsCleared
        ? result.remoteSessionRevoked ? '已退出当前设备。' : '已安全退出本机；服务端会话将在恢复网络后失效。'
        : '本机安全凭据未能清除，请解锁设备后重试。');
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
            accessibilityLabel="切换深浅主题"
            accessibilityRole="button"
            className="min-h-11 self-end justify-center px-2 active:opacity-70"
            onPress={() => setThemePreference(themePreference === 'system' ? 'light' : themePreference === 'light' ? 'dark' : 'system')}
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

        {isAuthenticated ? (
          <View className="my-10 rounded-xl bg-surface-light p-5 dark:bg-surface-dark" accessibilityLiveRegion="polite">
            <Text className="text-xl font-bold text-ink-light dark:text-ink-dark">已安全登录</Text>
            <Text className="mt-2 text-base text-muted-light dark:text-muted-dark">当前设备：{authentication.session?.deviceName}</Text>
            {authentication.userId ? <SyncStatusPanel userId={authentication.userId} /> : null}
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
