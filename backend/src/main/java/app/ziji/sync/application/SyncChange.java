package app.ziji.sync.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 已按 recipient 隔离的只读同步变更；payload 保留持久化墓碑或最小投递事实。 */
public record SyncChange(
	long sequence,
	String entityType,
	UUID entityId,
	int entityVersion,
	String changeType,
	int payloadVersion,
	Map<String, Object> payload) {

	private static final Set<String> CHANGE_TYPES = Set.of("UPSERT", "TOMBSTONE", "ACCESS_REVOKED", "BOOTSTRAP");

	public SyncChange {
		if (sequence < 1 || entityType == null || entityType.isBlank() || entityType.length() > 40
			|| entityId == null || entityVersion < 1 || !CHANGE_TYPES.contains(changeType) || payloadVersion != 1) {
			throw new IllegalArgumentException("同步变更事实无效。");
		}
		payload = payload == null ? null
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
