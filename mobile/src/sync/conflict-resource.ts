/** 仅允许服务端冻结的交易资源相对路径，避免把 Problem 当成任意 URL 请求。 */
const TRANSACTION_LOCATION = /^\/api\/v1\/transactions\/([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i;

export function parseTransactionResourceLocation(resourceLocation: string | null | undefined): string | null {
  if (typeof resourceLocation !== 'string') return null;
  const match = TRANSACTION_LOCATION.exec(resourceLocation);
  return match?.[1] ?? null;
}
