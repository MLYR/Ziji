package app.ziji.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话只读端口；Bearer 校验和稳定分页均不更新 lastSeenAt。 */
public interface DeviceSessionReadPort {

	boolean hasCurrentSession(UUID userId, UUID sessionId, Instant now);

	Optional<Position> findPositionForUser(UUID userId, UUID sessionId);

	List<Snapshot> listForUser(UUID userId, Position after, int maximumRecords);

	record Position(UUID sessionId, Instant issuedAt) {
	}

	record Snapshot(
		UUID sessionId,
		String deviceName,
		String deviceId,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		Instant lastSeenAt) {
	}
}
