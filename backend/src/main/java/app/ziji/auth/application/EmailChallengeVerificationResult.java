package app.ziji.auth.application;

/** 验证接口只区分统一成功和统一无效结果，避免泄露邮箱或挑战状态。 */
public enum EmailChallengeVerificationResult {
	VALID,
	INVALID
}
