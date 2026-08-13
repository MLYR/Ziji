import * as SecureStore from 'expo-secure-store';

const REFRESH_CREDENTIAL_KEY = 'ziji.refresh-credential';

export interface SecureCredentialStore {
  readRefreshCredential(): Promise<string | null>;
  writeRefreshCredential(value: string): Promise<void>;
  clearRefreshCredential(): Promise<void>;
}

export const secureCredentialStore: SecureCredentialStore = {
  // 刷新凭据只进入系统安全存储，不落入 SQLite 或普通偏好设置。
  readRefreshCredential: () => SecureStore.getItemAsync(REFRESH_CREDENTIAL_KEY),
  writeRefreshCredential: (value) =>
    SecureStore.setItemAsync(REFRESH_CREDENTIAL_KEY, value, {
      keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    }),
  clearRefreshCredential: () => SecureStore.deleteItemAsync(REFRESH_CREDENTIAL_KEY),
};
