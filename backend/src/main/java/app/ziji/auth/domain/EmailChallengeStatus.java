package app.ziji.auth.domain;

/** 邮箱验证码挑战的持久化生命周期状态。 */
public enum EmailChallengeStatus {
	ACTIVE,
	CONSUMED,
	REPLACED,
	EXPIRED,
	MAX_ATTEMPTS,
	SECURITY_REVOKED
}
