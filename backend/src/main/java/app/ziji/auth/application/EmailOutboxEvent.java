package app.ziji.auth.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 邮件消费者读取的已提交 outbox 最小事实；载荷解析失败也必须保留 claim 以便终态化。 */
public record EmailOutboxEvent(
	UUID id,
	UUID aggregateId,
	String aggregateType,
	String eventType,
	int payloadVersion,
	Map<String, Object> payload,
	Instant occurredAt,
	int attemptCount,
	boolean payloadJsonValid) {

	public EmailOutboxEvent(
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

	public EmailOutboxEvent {
		if (id == null || aggregateId == null || aggregateType == null || eventType == null || payloadVersion <= 0
			|| payload == null || occurredAt == null || attemptCount < 0) {
			throw new IllegalArgumentException("邮件 outbox 事件无效。");
		}
		payload = Map.copyOf(payload);
	}
}
