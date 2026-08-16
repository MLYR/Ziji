package app.ziji.ledger.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import app.ziji.ledger.application.LedgerOutbox;
import app.ziji.ledger.application.LedgerOutboxEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** 将冻结的最小 Ledger 事件写入既有 outbox_events 表；不承担消费或发布。 */
@Repository
public class PostgresLedgerOutbox implements LedgerOutbox {

	private static final String INSERT_SQL = """
		INSERT INTO outbox_events (
			id, aggregate_type, aggregate_id, event_type, payload, payload_version,
			occurred_at, published_at, attempt_count, next_attempt_at
		) VALUES (?, 'Transaction', ?, ?, CAST(? AS jsonb), 1, CAST(? AS timestamptz), NULL, 0, CAST(? AS timestamptz))
		""";

	private final DSLContext dsl;
	private final ObjectMapper objectMapper;

	public PostgresLedgerOutbox(DSLContext dsl, ObjectMapper objectMapper) {
		if (dsl == null || objectMapper == null) {
			throw new IllegalArgumentException("Ledger outbox 依赖不能为空。");
		}
		this.dsl = dsl;
		this.objectMapper = objectMapper;
	}

	@Override
	public void append(LedgerOutboxEvent event) {
		if (event == null) {
			throw new IllegalArgumentException("Ledger outbox 事件不能为空。");
		}
		try {
			String payload = objectMapper.writeValueAsString(event.payload());
			int changed = dsl.execute(INSERT_SQL, event.eventId(), event.aggregateId(), event.eventType().name(), payload,
				utc(event.occurredAt()), utc(event.occurredAt()));
			if (changed != 1) {
				throw new IllegalStateException("Ledger outbox 写入失败。");
			}
		} catch (JacksonException exception) {
			throw new LedgerOutboxPersistenceException("Ledger outbox 载荷序列化失败。", exception);
		}
	}

	private static OffsetDateTime utc(java.time.Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}
}
