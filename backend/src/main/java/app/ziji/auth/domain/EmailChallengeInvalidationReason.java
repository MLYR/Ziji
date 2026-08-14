package app.ziji.auth.domain;

/** 挑战失效原因，与数据库 invalidation_reason 枚举保持一致。 */
public enum EmailChallengeInvalidationReason {
	REPLACED,
	EXPIRED,
	MAX_ATTEMPTS,
	SECURITY_REVOKED;

	EmailChallengeStatus status() {
		return switch (this) {
			case REPLACED -> EmailChallengeStatus.REPLACED;
			case EXPIRED -> EmailChallengeStatus.EXPIRED;
			case MAX_ATTEMPTS -> EmailChallengeStatus.MAX_ATTEMPTS;
			case SECURITY_REVOKED -> EmailChallengeStatus.SECURITY_REVOKED;
		};
	}
}
