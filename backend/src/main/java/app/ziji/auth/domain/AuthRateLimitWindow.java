package app.ziji.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * V008 冻结的窗口和配额，枚举顺序即 IP→邮箱→设备、窗口从短到长的处理顺序。
 */
public enum AuthRateLimitWindow {
	IP_10M(RateLimitDimension.IP, "IP_10M", 600, 20),
	IP_24H(RateLimitDimension.IP, "IP_24H", 86_400, 100),
	EMAIL_60S(RateLimitDimension.EMAIL, "EMAIL_60S", 60, 1),
	EMAIL_1H(RateLimitDimension.EMAIL, "EMAIL_1H", 3_600, 5),
	EMAIL_24H(RateLimitDimension.EMAIL, "EMAIL_24H", 86_400, 10),
	DEVICE_1H(RateLimitDimension.DEVICE, "DEVICE_1H", 3_600, 10),
	DEVICE_24H(RateLimitDimension.DEVICE, "DEVICE_24H", 86_400, 30);

	private final RateLimitDimension dimension;
	private final String code;
	private final int seconds;
	private final int limit;

	AuthRateLimitWindow(RateLimitDimension dimension, String code, int seconds, int limit) {
		this.dimension = dimension;
		this.code = code;
		this.seconds = seconds;
		this.limit = limit;
	}

	public static List<AuthRateLimitWindow> ordered() {
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

	/** 按 UTC Unix epoch 计算固定窗口，边界瞬间归入新窗口。 */
	public Instant windowStartedAt(Instant now) {
		if (now == null) {
			throw new AuthDomainException("限流时间不能为空。");
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
