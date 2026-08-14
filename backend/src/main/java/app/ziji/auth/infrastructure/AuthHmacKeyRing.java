package app.ziji.auth.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 当前及上一版本 HMAC 密钥；轮换期两把密钥都参与摘要识别和验证码校验。
 */
public final class AuthHmacKeyRing {

	public static final Duration MINIMUM_PREVIOUS_RETENTION = Duration.ofHours(48);

	private final AuthHmacKey current;
	private final List<AuthHmacKey> keysInVersionOrder;

	public AuthHmacKeyRing(
		AuthHmacKey current,
		AuthHmacKey previous,
		Duration previousRetention) {
		if (current == null) {
			throw new AuthInfrastructureException("当前认证密钥未配置。");
		}
		if (previous != null && current.sameVersion(previous)) {
			throw new AuthInfrastructureException("认证密钥版本必须唯一。");
		}
		if (previous != null && previous.version() >= current.version()) {
			throw new AuthInfrastructureException("当前认证密钥版本必须高于上一版本。");
		}
		if (previous != null && (previousRetention == null
			|| previousRetention.compareTo(MINIMUM_PREVIOUS_RETENTION) < 0)) {
			throw new AuthInfrastructureException("上一版本认证密钥保留时间不足。");
		}
		this.current = current;
		List<AuthHmacKey> keys = new ArrayList<>();
		keys.add(current);
		if (previous != null) {
			keys.add(previous);
		}
		keys.sort(Comparator.comparingInt(AuthHmacKey::version));
		this.keysInVersionOrder = List.copyOf(keys);
	}

	public AuthHmacKey current() {
		return current;
	}

	public List<AuthHmacKey> keysInVersionOrder() {
		return keysInVersionOrder;
	}

	public Optional<AuthHmacKey> find(int version) {
		return keysInVersionOrder.stream()
			.filter(key -> key.version() == version)
			.findFirst();
	}
}
