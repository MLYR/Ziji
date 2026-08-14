package app.ziji.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** 数据库存储的刷新 Token 生命周期事实；已消费记录保留给后续重用攻击处置。 */
public final class StoredRefreshToken {

	private final UUID id;
	private final UUID sessionId;
	private final RefreshTokenHash tokenHash;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final Instant consumedAt;
	private final Instant revokedAt;
	private final UUID replacedById;
	private final Instant createdAt;

	private StoredRefreshToken(
		UUID id,
		UUID sessionId,
		RefreshTokenHash tokenHash,
		Instant issuedAt,
		Instant expiresAt,
		Instant consumedAt,
		Instant revokedAt,
		UUID replacedById,
		Instant createdAt) {
		this.id = require(id, "刷新凭据 ID");
		this.sessionId = require(sessionId, "刷新凭据会话 ID");
		this.tokenHash = require(tokenHash, "刷新凭据摘要");
		this.issuedAt = require(issuedAt, "刷新凭据签发时间");
		this.expiresAt = require(expiresAt, "刷新凭据到期时间");
		this.consumedAt = consumedAt;
		this.revokedAt = revokedAt;
		this.replacedById = replacedById;
		this.createdAt = require(createdAt, "刷新凭据创建时间");
	}

	public static StoredRefreshToken issue(
		UUID id,
		UUID sessionId,
		RefreshTokenHash tokenHash,
		Instant issuedAt,
		Instant expiresAt) {
		// V011 冻结新凭据 createdAt 与 issuedAt 相等，且由数据库触发器再次验证。
		return new StoredRefreshToken(id, sessionId, tokenHash, issuedAt, expiresAt, null, null, null, issuedAt);
	}

	public static StoredRefreshToken restore(
		UUID id,
		UUID sessionId,
		RefreshTokenHash tokenHash,
		Instant issuedAt,
		Instant expiresAt,
		Instant consumedAt,
		Instant revokedAt,
		UUID replacedById,
		Instant createdAt) {
		return new StoredRefreshToken(id, sessionId, tokenHash, issuedAt, expiresAt, consumedAt, revokedAt,
			replacedById, createdAt);
	}

	public UUID id() {
		return id;
	}

	public UUID sessionId() {
		return sessionId;
	}

	public String tokenHash() {
		return tokenHash.value();
	}

	public Instant issuedAt() {
		return issuedAt;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant consumedAt() {
		return consumedAt;
	}

	public Instant revokedAt() {
		return revokedAt;
	}

	public UUID replacedById() {
		return replacedById;
	}

	public Instant createdAt() {
		return createdAt;
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}
}
