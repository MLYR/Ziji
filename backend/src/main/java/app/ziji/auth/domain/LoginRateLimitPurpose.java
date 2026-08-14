package app.ziji.auth.domain;

/** 密码登录限流用途；与验证码用途严格隔离，不得跨操作复用限流主体。 */
public enum LoginRateLimitPurpose {
	LOGIN
}
