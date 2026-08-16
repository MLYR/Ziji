package app.ziji.sync.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 同步消费者读取的已提交 outbox 最小事实。 */
public record SyncOutboxEvent(
	UUID id,
	UUID aggregateId,
	String aggregateType,
	String eventType,
	int payloadVersion,
	Map<String, Object> payload,
	Instant occurredAt,
	int attemptCount,
	boolean payloadJsonValid) {

	public SyncOutboxEvent(
		UUID id,
		UUID aggregateId,
		String aggregateType,
		String eventType,
		int payloadVersion,
		Map<String, Object> payload,
		Instant occurredAt,
		int attemptCount) {
		this(id, aggregateId, aggregateType, eventType, payloadVersion, payload, occurredAt, attemptCount, true);
	}

	public SyncOutboxEvent {
		if (id == null || aggregateId == null || aggregateType == null || eventType == null || payloadVersion <= 0
			|| payload == null || occurredAt == null || attemptCount < 0) {
			throw new IllegalArgumentException("同步 outbox 事件无效。");
		}
		payload = Map.copyOf(payload);
	}
}
