package app.ziji.auth.application;

/** 已消费 Refresh Token 的安全处置结果；不含 Token、摘要或会话归属信息。 */
public record RefreshTokenReuseResult(Status status) {

	public enum Status {
		REVOKED,
		ALREADY_REVOKED,
		NOT_REUSED
	}

	public RefreshTokenReuseResult {
		if (status == null) {
			throw new IllegalArgumentException("刷新凭据重用结果无效。");
		}
	}

	public static RefreshTokenReuseResult revoked() {
		return new RefreshTokenReuseResult(Status.REVOKED);
	}

	public static RefreshTokenReuseResult alreadyRevoked() {
		return new RefreshTokenReuseResult(Status.ALREADY_REVOKED);
	}

	public static RefreshTokenReuseResult notReused() {
		return new RefreshTokenReuseResult(Status.NOT_REUSED);
	}
}
