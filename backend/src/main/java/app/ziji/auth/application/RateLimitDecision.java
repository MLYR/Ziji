package app.ziji.auth.application;

/** PostgreSQL 固定窗口占用结果；拒绝也代表计数已在事务中提交。 */
public final class RateLimitDecision {

	private final boolean allowed;
	private final int retryAfterSeconds;

	private RateLimitDecision(boolean allowed, int retryAfterSeconds) {
		this.allowed = allowed;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public static RateLimitDecision permitted() {
		return new RateLimitDecision(true, 0);
	}

	public static RateLimitDecision denied(int retryAfterSeconds) {
		return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
	}

	public boolean allowed() {
		return allowed;
	}

	public int retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
