import * as SecureStore from 'expo-secure-store';

const REFRESH_CREDENTIAL_KEY = 'ziji.refresh-credential';
const DEVICE_ID_KEY = 'ziji.device-id';

export interface SecureCredentialStore {
  readRefreshCredential(): Promise<string | null>;
  writeRefreshCredential(value: string): Promise<void>;
  clearRefreshCredential(): Promise<void>;
  readDeviceId(): Promise<string | null>;
  writeDeviceId(value: string): Promise<void>;
}

export const secureCredentialStore: SecureCredentialStore = {
  // 刷新凭据只进入系统安全存储，不落入 SQLite 或普通偏好设置。
  readRefreshCredential: () => SecureStore.getItemAsync(REFRESH_CREDENTIAL_KEY),
  writeRefreshCredential: (value) =>
    SecureStore.setItemAsync(REFRESH_CREDENTIAL_KEY, value, {
      keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    }),
  clearRefreshCredential: () => SecureStore.deleteItemAsync(REFRESH_CREDENTIAL_KEY),
  // deviceId 是稳定但不可信的设备标识；仍使用安全存储避免进入 SQLite 或普通偏好设置。
  readDeviceId: () => SecureStore.getItemAsync(DEVICE_ID_KEY),
  writeDeviceId: (value) =>
    SecureStore.setItemAsync(DEVICE_ID_KEY, value, {
      keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    }),
};
