package app.ziji.auth.infrastructure;

import java.time.Duration;

/** 公开幂等主体专用的当前/上一 HMAC 密钥；与认证限流密钥完全分离。 */
public final class IdempotencyHmacKeyRing {

	public static final Duration MINIMUM_PREVIOUS_RETENTION = Duration.ofDays(7);

	private final AuthHmacKey current;
	private final AuthHmacKey previous;

	public IdempotencyHmacKeyRing(
		AuthHmacKey current,
		AuthHmacKey previous,
		Duration previousRetention) {
		if (current == null) {
			throw new AuthInfrastructureException("当前幂等 HMAC 密钥未配置。");
		}
		// V009 使用 smallint 保存密钥版本，启动时拒绝无法持久化的配置。
		if (current.version() > Short.MAX_VALUE || previous != null && previous.version() > Short.MAX_VALUE) {
			throw new AuthInfrastructureException("幂等 HMAC 密钥版本配置无效。");
		}
		if (previous != null && (current.sameVersion(previous) || previous.version() >= current.version())) {
			throw new AuthInfrastructureException("幂等 HMAC 密钥版本配置无效。");
		}
		if (previous != null && (previousRetention == null
			|| previousRetention.compareTo(MINIMUM_PREVIOUS_RETENTION) < 0)) {
			throw new AuthInfrastructureException("上一版本幂等 HMAC 密钥保留时间不足。");
		}
		this.current = current;
		this.previous = previous;
	}

	public AuthHmacKey current() {
		return current;
	}

	public AuthHmacKey previous() {
		return previous;
	}
}
