package app.ziji.auth.domain;

/** 验证码固定窗口的三类限流主体。 */
public enum RateLimitDimension {
	IP,
	EMAIL,
	DEVICE
}
