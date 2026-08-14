package app.ziji.auth.domain;

import java.security.SecureRandom;
import java.util.Base64;

/** 仅在边界短暂存在的刷新凭据原文；不提供会泄漏原文的 toString。 */
public final class RefreshToken {

	public static final String PREFIX = "rt1_";
	private static final int RANDOM_BYTES = 32;

	private final String value;

	private RefreshToken(String value) {
		this.value = value;
	}

	public static RefreshToken generate(SecureRandom random) {
		if (random == null) {
			throw new AuthDomainException("安全随机源不能为空。");
		}
		byte[] bytes = new byte[RANDOM_BYTES];
		random.nextBytes(bytes);
		return new RefreshToken(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
	}

	public static RefreshToken fromClient(String value) {
		if (!isWellFormed(value)) {
			throw new AuthDomainException("刷新凭据格式无效。");
		}
		return new RefreshToken(value);
	}

	public static boolean isWellFormed(String value) {
		if (value == null || !value.startsWith(PREFIX)) {
			return false;
		}
		String encoded = value.substring(PREFIX.length());
		if (encoded.indexOf('=') >= 0 || !encoded.matches("[A-Za-z0-9_-]{43}")) {
			return false;
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(encoded);
			// 重新编码确认 Base64URL 是无填充且规范的，防止多个字符串映射到同一凭据事实。
			return decoded.length == RANDOM_BYTES
				&& Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public String value() {
		return value;
	}
}
