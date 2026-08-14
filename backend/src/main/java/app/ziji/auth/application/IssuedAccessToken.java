package app.ziji.auth.application;

import java.time.Instant;

/** 新签发 Access Token 的短暂交付结果；不提供会暴露凭据的 toString。 */
public final class IssuedAccessToken {

	private final String value;
	private final Instant expiresAt;

	public IssuedAccessToken(String value, Instant expiresAt) {
		if (value == null || value.isBlank() || expiresAt == null) {
			throw new IllegalArgumentException("Access Token 签发结果无效。");
		}
		this.value = value;
		this.expiresAt = expiresAt;
	}

	public String value() {
		return value;
	}

	public Instant expiresAt() {
		return expiresAt;
	}
}
