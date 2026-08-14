package app.ziji.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.SessionRevocationReason;
import app.ziji.auth.domain.StoredRefreshToken;

/** 会话和刷新凭据持久化端口；实现必须在调用方 REQUIRED 事务中使用 PostgreSQL 行锁。 */
public interface DeviceSessionStore {

	void revokeActiveSessionForReplacement(UUID userId, String deviceId, Instant revokedAt);

	void insertSession(DeviceSession session);

	void insertRefreshToken(StoredRefreshToken refreshToken);

	Optional<RefreshTokenSessionState> findRefreshTokenForUpdate(String tokenHash);

	Optional<DeviceSession> findSessionForUserForUpdate(UUID userId, UUID sessionId);

	List<DeviceSession> findActiveSessionsForUserForUpdate(UUID userId);

	boolean revokeSession(UUID sessionId, Instant revokedAt, SessionRevocationReason reason);

	void revokeCurrentRefreshTokens(UUID sessionId, Instant revokedAt);

	boolean consumeRefreshToken(UUID tokenId, Instant consumedAt);

	boolean linkReplacement(UUID tokenId, UUID replacementTokenId);

	boolean updateLastSeen(UUID sessionId, Instant lastSeenAt);
}
