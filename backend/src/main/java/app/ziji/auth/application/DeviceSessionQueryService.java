package app.ziji.auth.application;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 设备会话读取编排；使用 keyset 分页且普通读取不改变会话安全事实。 */
public final class DeviceSessionQueryService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;

	private final DeviceSessionReadPort readPort;
	private final Clock clock;

	public DeviceSessionQueryService(DeviceSessionReadPort readPort, Clock clock) {
		if (readPort == null || clock == null) {
			throw new DeviceSessionQueryValidationException();
		}
		this.readPort = readPort;
		this.clock = clock;
	}

	/** JWT 已验签后只读确认 V011 当前会话；不存在、历史、撤销或过期均失效。 */
	public boolean hasCurrentSession(UUID userId, UUID sessionId) {
		return userId != null && sessionId != null && readPort.hasCurrentSession(userId, sessionId, clock.instant());
	}

	public DeviceSessionPage listUserSessions(UUID userId, Integer requestedLimit, String cursor) {
		if (userId == null) {
			throw new DeviceSessionQueryValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new DeviceSessionQueryValidationException();
		}
		DeviceSessionReadPort.Position after = resolveCursor(userId, cursor);
		List<DeviceSessionReadPort.Snapshot> fetched = readPort.listForUser(userId, after, limit + 1);
		boolean hasMore = fetched.size() > limit;
		int returnedCount = hasMore ? limit : fetched.size();
		List<DeviceSessionSummary> sessions = new ArrayList<>(returnedCount);
		Instant now = clock.instant();
		for (int index = 0; index < returnedCount; index++) {
			sessions.add(toSummary(fetched.get(index), now));
		}
		String nextCursor = hasMore ? encode(fetched.get(returnedCount - 1).sessionId()) : null;
		return new DeviceSessionPage(sessions, nextCursor, hasMore);
	}

	private DeviceSessionReadPort.Position resolveCursor(UUID userId, String cursor) {
		if (cursor == null) {
			return null;
		}
		UUID sessionId = decode(cursor);
		return readPort.findPositionForUser(userId, sessionId).orElseThrow(DeviceSessionQueryValidationException::new);
	}

	private static DeviceSessionSummary toSummary(DeviceSessionReadPort.Snapshot snapshot, Instant now) {
		DeviceSessionSummary.Status status = snapshot.revokedAt() != null
			? DeviceSessionSummary.Status.REVOKED
			: now.isBefore(snapshot.expiresAt())
				? DeviceSessionSummary.Status.ACTIVE
				: DeviceSessionSummary.Status.EXPIRED;
		return new DeviceSessionSummary(
			snapshot.sessionId(), snapshot.deviceName(), snapshot.deviceId(), snapshot.issuedAt(), snapshot.lastSeenAt(), status);
	}

	private static String encode(UUID sessionId) {
		if (sessionId == null) {
			throw new DeviceSessionQueryValidationException();
		}
		ByteBuffer bytes = ByteBuffer.allocate(16)
			.putLong(sessionId.getMostSignificantBits())
			.putLong(sessionId.getLeastSignificantBits());
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
	}

	private static UUID decode(String cursor) {
		if (cursor == null || cursor.length() != 22) {
			throw new DeviceSessionQueryValidationException();
		}
		try {
			byte[] bytes = Base64.getUrlDecoder().decode(cursor);
			if (bytes.length != 16) {
				throw new DeviceSessionQueryValidationException();
			}
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			return new UUID(buffer.getLong(), buffer.getLong());
		} catch (IllegalArgumentException exception) {
			throw new DeviceSessionQueryValidationException();
		}
	}
}
