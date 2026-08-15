package app.ziji.shared.application;

/** PostgreSQL 5 秒行锁等待到期；应用层统一映射为固定 Retry-After 的处理中结果。 */
public final class IdempotencyLockTimeoutException extends RuntimeException {

	public IdempotencyLockTimeoutException(Throwable cause) {
		super("幂等请求仍在处理中。", cause);
	}
}
