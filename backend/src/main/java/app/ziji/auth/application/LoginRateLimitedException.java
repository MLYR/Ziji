package app.ziji.auth.application;

/**
 * 密码登录固定窗口限流异常；计数已在数据库事务中提交后才抛出，retryAfterSeconds 取所有超限窗口的最长剩余秒数。
 */
public final class LoginRateLimitedException extends RuntimeException {

	private static final String CODE = "RATE_LIMITED";

	private final int retryAfterSeconds;

	public LoginRateLimitedException(int retryAfterSeconds) {
		super("登录请求过于频繁。");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public int retryAfterSeconds() {
		return retryAfterSeconds;
	}

	public String code() {
		return CODE;
	}
}
