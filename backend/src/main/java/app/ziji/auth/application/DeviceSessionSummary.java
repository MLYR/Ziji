package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

/** HTTP 可见的最小设备会话视图；绝不包含撤销原因、基线版本或 Token 摘要。 */
public record DeviceSessionSummary(
	UUID sessionId,
	String deviceName,
	String deviceId,
	Instant createdAt,
	Instant lastSeenAt,
	Status status) {

	public enum Status {
		ACTIVE,
		REVOKED,
		EXPIRED
	}
}
