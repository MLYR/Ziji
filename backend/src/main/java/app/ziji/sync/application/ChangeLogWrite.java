package app.ziji.sync.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** change_log 的框架无关写入载荷。 */
public record ChangeLogWrite(
	String entityType,
	UUID entityId,
	int entityVersion,
	String changeType,
	UUID recipientUserId,
	UUID accountId,
	Instant changedAt,
	Map<String, Object> payload) {
	public ChangeLogWrite {
		if (entityType == null || entityType.isBlank() || entityId == null || entityVersion <= 0
			|| changeType == null || recipientUserId == null || changedAt == null || payload == null) {
			throw new IllegalArgumentException("change_log 写入载荷无效。");
		}
		payload = Map.copyOf(payload);
	}
}
