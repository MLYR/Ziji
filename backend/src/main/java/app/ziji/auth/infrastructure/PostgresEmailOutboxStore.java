package app.ziji.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.application.EmailOutboxClaim;
import app.ziji.auth.application.EmailOutboxEvent;
import app.ziji.auth.application.EmailOutboxStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 使用 V014/V018 subscription 与 consumer receipt 实现 EMAIL 消费者独立 claim、lease、重试和终态。 */
@Repository
public class PostgresEmailOutboxStore implements EmailOutboxStore {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public PostgresEmailOutboxStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		if (jdbc == null || objectMapper == null) {
			throw new IllegalArgumentException("邮件 outbox 依赖不能为空。");
		}
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<EmailOutboxClaim> claimNext(String consumerName, Instant now, Instant leaseUntil) {
		if (consumerName == null || now == null || leaseUntil == null) {
			throw new IllegalArgumentException("邮件 outbox claim 参数不能为空。");
		}
		createDueReceipts(consumerName, now);
		List<Candidate> candidates = jdbc.query("""
			SELECT r.outbox_event_id, r.attempt_count, e.aggregate_id, e.aggregate_type, e.event_type,
				e.payload_version, e.payload::text, e.occurred_at
			FROM outbox_consumer_receipts r
			JOIN outbox_events e ON e.id = r.outbox_event_id
			WHERE r.consumer_name = ?
			  AND (
				(r.status IN ('PENDING', 'FAILED_RETRYABLE') AND r.next_attempt_at <= CAST(? AS timestamptz))
				OR (r.status = 'PROCESSING' AND r.lease_expires_at <= CAST(? AS timestamptz))
			  )
			ORDER BY r.next_attempt_at, r.outbox_event_id
			FOR UPDATE OF r SKIP LOCKED
			LIMIT 1
			""", (result, rowNum) -> new Candidate(
			result.getObject("outbox_event_id", UUID.class),
			result.getInt("attempt_count"),
			result.getObject("aggregate_id", UUID.class),
			result.getString("aggregate_type"),
			result.getString("event_type"),
			result.getInt("payload_version"),
			result.getString("payload"),
			result.getTimestamp("occurred_at").toInstant()),
			consumerName, Timestamp.from(now), Timestamp.from(now));
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		Candidate candidate = candidates.getFirst();
		UUID claimToken = UUID.randomUUID();
		int updated = jdbc.update("""
			UPDATE outbox_consumer_receipts
			SET status = 'PROCESSING', claim_token = ?, lease_expires_at = ?,
				attempt_count = attempt_count + 1, completed_at = NULL, failed_at = NULL,
				error_code = NULL, updated_at = ?
			WHERE consumer_name = ? AND outbox_event_id = ?
			""", claimToken, Timestamp.from(leaseUntil), Timestamp.from(now), consumerName, candidate.eventId());
		if (updated != 1) {
			throw new IllegalStateException("邮件 outbox receipt 抢占失败。");
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = objectMapper.readValue(candidate.payload(), Map.class);
			return Optional.of(new EmailOutboxClaim(consumerName, new EmailOutboxEvent(
				candidate.eventId(), candidate.aggregateId(), candidate.aggregateType(), candidate.eventType(),
				candidate.payloadVersion(), payload, candidate.occurredAt(), candidate.attemptCount() + 1, true), claimToken));
		} catch (Exception exception) {
			// 非法 JSON 仍需携带当前 claim 进入 application 终态，避免回滚后永久停留在 PENDING。
			return Optional.of(new EmailOutboxClaim(consumerName, new EmailOutboxEvent(
				candidate.eventId(), candidate.aggregateId(), candidate.aggregateType(), candidate.eventType(),
				candidate.payloadVersion(), Map.of(), candidate.occurredAt(), candidate.attemptCount() + 1, false), claimToken));
		}
	}

	@Override
	public void markSucceeded(EmailOutboxClaim claim, Instant completedAt) {
		int updated = jdbc.update("""
			UPDATE outbox_consumer_receipts
			SET status = 'SUCCEEDED', claim_token = NULL, lease_expires_at = NULL,
				completed_at = ?, failed_at = NULL, error_code = NULL, updated_at = ?
			WHERE consumer_name = ? AND outbox_event_id = ? AND status = 'PROCESSING' AND claim_token = ?
			""", Timestamp.from(completedAt), Timestamp.from(completedAt), claim.consumerName(),
			claim.event().id(), claim.claimToken());
		if (updated != 1) {
			throw new IllegalStateException("邮件 outbox receipt 完成状态写入失败。");
		}
	}

	@Override
	public void markRetryable(EmailOutboxClaim claim, Instant failedAt, Instant nextAttemptAt, String errorCode) {
		int updated = jdbc.update("""
			UPDATE outbox_consumer_receipts
			SET status = 'FAILED_RETRYABLE', claim_token = NULL, lease_expires_at = NULL,
				failed_at = ?, error_code = ?, next_attempt_at = ?, updated_at = ?
			WHERE consumer_name = ? AND outbox_event_id = ? AND status = 'PROCESSING' AND claim_token = ?
			""", Timestamp.from(failedAt), errorCode, Timestamp.from(nextAttemptAt), Timestamp.from(failedAt),
			claim.consumerName(), claim.event().id(), claim.claimToken());
		if (updated != 1) {
			throw new IllegalStateException("邮件 outbox receipt 可重试状态写入失败。");
		}
	}

	@Override
	public void markFinal(EmailOutboxClaim claim, Instant failedAt, String errorCode) {
		int updated = jdbc.update("""
			UPDATE outbox_consumer_receipts
			SET status = 'FAILED_FINAL', claim_token = NULL, lease_expires_at = NULL,
				failed_at = ?, error_code = ?, updated_at = ?
			WHERE consumer_name = ? AND outbox_event_id = ? AND status = 'PROCESSING' AND claim_token = ?
			""", Timestamp.from(failedAt), errorCode, Timestamp.from(failedAt), claim.consumerName(),
			claim.event().id(), claim.claimToken());
		if (updated != 1) {
			throw new IllegalStateException("邮件 outbox receipt 最终失败状态写入失败。");
		}
	}

	private void createDueReceipts(String consumerName, Instant now) {
		jdbc.update("""
			INSERT INTO outbox_consumer_receipts (
				consumer_name, outbox_event_id, status, attempt_count, next_attempt_at, created_at, updated_at)
			SELECT s.consumer_name, e.id, 'PENDING', 0, e.occurred_at, CAST(? AS timestamptz), CAST(? AS timestamptz)
			FROM outbox_consumer_subscriptions s
			JOIN outbox_events e
			  ON e.aggregate_type = s.aggregate_type AND e.event_type = s.event_type
			 AND s.subscribed_from <= e.occurred_at
			 AND (s.subscribed_until IS NULL OR e.occurred_at < s.subscribed_until)
			WHERE s.consumer_name = ?
			  AND e.occurred_at <= CAST(? AS timestamptz)
			ON CONFLICT (consumer_name, outbox_event_id) DO NOTHING
			""", Timestamp.from(now), Timestamp.from(now), consumerName, Timestamp.from(now));
	}

	private record Candidate(
		UUID eventId,
		int attemptCount,
		UUID aggregateId,
		String aggregateType,
		String eventType,
		int payloadVersion,
		String payload,
		Instant occurredAt) {
	}
}
