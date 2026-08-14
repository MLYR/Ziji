package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

/** 会话创建或正常轮换的传输无关结果；HTTP/Cookie/Mobile 编排由后续用例负责。 */
public final class SessionTokenResult {

	private final UUID sessionId;
	private final String deviceName;
	private final String deviceId;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final Instant lastSeenAt;
	private final String accessToken;
	private final Instant accessTokenExpiresAt;
	private final String refreshToken;

	public SessionTokenResult(
		UUID sessionId,
		String deviceName,
		String deviceId,
		Instant issuedAt,
		Instant expiresAt,
		Instant lastSeenAt,
		String accessToken,
		Instant accessTokenExpiresAt,
		String refreshToken) {
		if (sessionId == null || deviceName == null || issuedAt == null || expiresAt == null || lastSeenAt == null
			|| accessToken == null || accessToken.isBlank() || accessTokenExpiresAt == null
			|| refreshToken == null || refreshToken.isBlank()) {
			throw new IllegalArgumentException("会话凭据结果无效。");
		}
		this.sessionId = sessionId;
		this.deviceName = deviceName;
		this.deviceId = deviceId;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.lastSeenAt = lastSeenAt;
		this.accessToken = accessToken;
		this.accessTokenExpiresAt = accessTokenExpiresAt;
		this.refreshToken = refreshToken;
	}

	public UUID sessionId() {
		return sessionId;
	}

	public String deviceName() {
		return deviceName;
	}

	public String deviceId() {
		return deviceId;
	}

	public Instant issuedAt() {
		return issuedAt;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant lastSeenAt() {
		return lastSeenAt;
	}

	public String accessToken() {
		return accessToken;
	}

	public Instant accessTokenExpiresAt() {
		return accessTokenExpiresAt;
	}

	public String refreshToken() {
		return refreshToken;
	}
}
