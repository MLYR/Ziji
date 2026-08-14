package app.ziji.auth.domain;

/** V1 邮箱验证码用途；用途也是挑战和限流桶的隔离边界。 */
public enum EmailChallengePurpose {
	REGISTER,
	RESET_PASSWORD
}
