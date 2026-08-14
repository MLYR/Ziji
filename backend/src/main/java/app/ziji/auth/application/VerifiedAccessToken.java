package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

/** 已验证的非敏感 JWT Claims；不含编码 Token、签名或任何密钥材料。 */
public final class VerifiedAccessToken {

	private final UUID userId;
	private final UUID sessionId;
	private final UUID tokenId;
	private final Instant issuedAt;
	private final Instant notBefore;
	private final Instant expiresAt;
	private final String keyId;

	public VerifiedAccessToken(
		UUID userId,
		UUID sessionId,
		UUID tokenId,
		Instant issuedAt,
		Instant notBefore,
		Instant expiresAt,
		String keyId) {
		if (userId == null || sessionId == null || tokenId == null
			|| issuedAt == null || notBefore == null || expiresAt == null || keyId == null || keyId.isBlank()) {
			throw new IllegalArgumentException("Access Token Claims 无效。");
		}
		this.userId = userId;
		this.sessionId = sessionId;
		this.tokenId = tokenId;
		this.issuedAt = issuedAt;
		this.notBefore = notBefore;
		this.expiresAt = expiresAt;
		this.keyId = keyId;
	}

	public UUID userId() {
		return userId;
	}

	public UUID sessionId() {
		return sessionId;
	}

	public UUID tokenId() {
		return tokenId;
	}

	public Instant issuedAt() {
		return issuedAt;
	}

	public Instant notBefore() {
		return notBefore;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public String keyId() {
		return keyId;
	}
}
