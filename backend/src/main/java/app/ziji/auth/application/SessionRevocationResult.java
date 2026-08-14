package app.ziji.auth.application;

/** 设备撤销的传输无关结果；不可见资源与不存在资源共用 NOT_FOUND，避免泄露归属。 */
public record SessionRevocationResult(Status status, int revokedSessionCount) {

	public enum Status {
		REVOKED,
		ALREADY_REVOKED,
		NOT_FOUND
	}

	public SessionRevocationResult {
		if (status == null || revokedSessionCount < 0 || (status == Status.NOT_FOUND && revokedSessionCount != 0)) {
			throw new IllegalArgumentException("会话撤销结果无效。");
		}
	}

	public static SessionRevocationResult revoked(int count) {
		return new SessionRevocationResult(Status.REVOKED, count);
	}

	public static SessionRevocationResult alreadyRevoked() {
		return new SessionRevocationResult(Status.ALREADY_REVOKED, 0);
	}

	public static SessionRevocationResult notFound() {
		return new SessionRevocationResult(Status.NOT_FOUND, 0);
	}
}
