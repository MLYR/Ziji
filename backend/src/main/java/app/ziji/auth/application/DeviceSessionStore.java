package app.ziji.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.StoredRefreshToken;

/** 会话和刷新凭据持久化端口；实现必须在调用方 REQUIRED 事务中使用 PostgreSQL 行锁。 */
public interface DeviceSessionStore {

	void revokeActiveSessionForReplacement(UUID userId, String deviceId, Instant revokedAt);

	void insertSession(DeviceSession session);

	void insertRefreshToken(StoredRefreshToken refreshToken);

	Optional<RefreshTokenSessionState> findRefreshTokenForUpdate(String tokenHash);

	boolean consumeRefreshToken(UUID tokenId, Instant consumedAt);

	boolean linkReplacement(UUID tokenId, UUID replacementTokenId);

	boolean updateLastSeen(UUID sessionId, Instant lastSeenAt);
}
