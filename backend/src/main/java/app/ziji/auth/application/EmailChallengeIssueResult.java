package app.ziji.auth.application;

/** 签发结果只暴露接受或限流及等待时间，不暴露邮箱是否存在或挑战内部标识。 */
public final class EmailChallengeIssueResult {

	private final boolean accepted;
	private final int expiresInSeconds;
	private final int retryAfterSeconds;

	private EmailChallengeIssueResult(boolean accepted, int expiresInSeconds, int retryAfterSeconds) {
		this.accepted = accepted;
		this.expiresInSeconds = expiresInSeconds;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public static EmailChallengeIssueResult accepted(int expiresInSeconds) {
		return new EmailChallengeIssueResult(true, expiresInSeconds, 0);
	}

	public static EmailChallengeIssueResult rateLimited(int retryAfterSeconds) {
		return new EmailChallengeIssueResult(false, 0, Math.max(1, retryAfterSeconds));
	}

	public boolean accepted() {
		return accepted;
	}

	public int expiresInSeconds() {
		return expiresInSeconds;
	}

	public int retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
