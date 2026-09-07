import { createClientUuid } from '@/lib/client-id';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('client UUID generation', () => {
  const originalCrypto = globalThis.crypto;

  afterEach(() => {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: originalCrypto });
  });

  it('uses the native randomUUID when available', () => {
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: { randomUUID: () => 'native-client-uuid' },
    });

    expect(createClientUuid()).toBe('native-client-uuid');
  });

  it('keeps working on runtimes without Web Crypto', () => {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined });

    expect(createClientUuid()).toMatch(UUID_PATTERN);
  });
});
