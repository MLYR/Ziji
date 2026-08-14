package app.ziji.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * V010 冻结的密码登录固定窗口和配额；登录只使用 IP 与 EMAIL 两个维度，枚举声明顺序即处理顺序。
 * 窗口边界公式与 {@link AuthRateLimitWindow} 同为按 UTC Unix epoch 对齐的固定窗口，但属独立安全策略，
 * 因此单独建模，不与验证码窗口枚举混用以避免误用 DEVICE 或共享配额。
 */
public enum LoginRateLimitWindow {
	IP_10M(RateLimitDimension.IP, "LOGIN_IP_10M", 600, 30),
	IP_24H(RateLimitDimension.IP, "LOGIN_IP_24H", 86_400, 300),
	EMAIL_15M(RateLimitDimension.EMAIL, "LOGIN_EMAIL_15M", 900, 10),
	EMAIL_24H(RateLimitDimension.EMAIL, "LOGIN_EMAIL_24H", 86_400, 50);

	private final RateLimitDimension dimension;
	private final String code;
	private final int seconds;
	private final int limit;

	LoginRateLimitWindow(RateLimitDimension dimension, String code, int seconds, int limit) {
		this.dimension = dimension;
		this.code = code;
		this.seconds = seconds;
		this.limit = limit;
	}

	public static List<LoginRateLimitWindow> ordered() {
		return List.of(values());
	}

	public RateLimitDimension dimension() {
		return dimension;
	}

	public String code() {
		return code;
	}

	public int seconds() {
		return seconds;
	}

	public int limit() {
		return limit;
	}

	/** 按 UTC Unix epoch 计算固定窗口，边界瞬间归入新窗口，保证多实例不会各自创建滑动窗口。 */
	public Instant windowStartedAt(Instant now) {
		if (now == null) {
			throw new AuthDomainException("登录限流时间不能为空。");
		}
		long startedEpochSecond = Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
		return Instant.ofEpochSecond(startedEpochSecond);
	}

	public Instant windowEndsAt(Instant now) {
		return windowStartedAt(now).plusSeconds(seconds);
	}

	public int retryAfterSeconds(Instant now) {
		Duration remaining = Duration.between(now, windowEndsAt(now));
		long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
		return (int) Math.max(1, seconds);
	}
}
