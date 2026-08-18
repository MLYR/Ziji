import { parseTransactionResourceLocation } from './conflict-resource';

describe('conflict transaction resource location', () => {
  it('仅提取精确的同源交易 UUID 路径', () => {
    expect(parseTransactionResourceLocation('/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000')).toBe('123e4567-e89b-42d3-a456-426614174000');
  });

  it.each([
    'https://evil.example/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000',
    '//evil.example/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000',
    '/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000?next=/x',
    '/api/v1/transactions/123e4567-e89b-42d3-a456-426614174000#fragment',
    '/api/v1/transactions/../users/me',
    '/api/v1/accounts/123e4567-e89b-42d3-a456-426614174000',
    '/api/v1/transactions/not-a-uuid',
  ])('拒绝不安全 location：%s', (resourceLocation) => {
    expect(parseTransactionResourceLocation(resourceLocation)).toBeNull();
  });
});
