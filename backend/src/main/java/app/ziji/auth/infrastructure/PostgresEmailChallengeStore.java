package app.ziji.auth.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.application.EmailChallengeStore;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengeInvalidationReason;
import app.ziji.auth.domain.EmailChallengePurpose;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** 使用 PostgreSQL 行锁和条件更新实现挑战替换、过期、错误计数和一次性消费。 */
@Repository
public final class PostgresEmailChallengeStore implements EmailChallengeStore {

	private static final String FIND_LATEST_FOR_UPDATE_SQL = """
		SELECT id, purpose, email_normalized, code_hash, expires_at, attempt_count,
			max_attempts, consumed_at, invalidated_at, invalidation_reason, created_at
		FROM email_challenges
		WHERE email_normalized = ? AND purpose = ?
		ORDER BY created_at DESC, id DESC
		LIMIT 1
		FOR UPDATE
		""";

	private static final String INSERT_SQL = """
		INSERT INTO email_challenges
			(id, purpose, email_normalized, code_hash, expires_at, attempt_count,
			 max_attempts, consumed_at, invalidated_at, invalidation_reason, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String REPLACE_ACTIVE_SQL = """
		UPDATE email_challenges
		SET invalidated_at = ?, invalidation_reason = 'REPLACED'
		WHERE email_normalized = ? AND purpose = ?
			AND consumed_at IS NULL AND invalidated_at IS NULL
		""";

	private static final String MARK_EXPIRED_SQL = """
		UPDATE email_challenges
		SET invalidated_at = ?, invalidation_reason = 'EXPIRED'
		WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
			AND expires_at <= ?
		""";

	private static final String CONSUME_SQL = """
		UPDATE email_challenges
		SET consumed_at = ?
		WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
			AND expires_at > ? AND attempt_count < max_attempts
		""";

	private static final String RECORD_FAILED_ATTEMPT_SQL = """
		UPDATE email_challenges
		SET attempt_count = attempt_count + 1,
			invalidated_at = CASE
				WHEN attempt_count + 1 >= max_attempts THEN ? ELSE NULL END,
			invalidation_reason = CASE
				WHEN attempt_count + 1 >= max_attempts THEN 'MAX_ATTEMPTS' ELSE NULL END
		WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
			AND expires_at > ? AND attempt_count < max_attempts
		""";

	private final DSLContext dsl;

	public PostgresEmailChallengeStore(DSLContext dsl) {
		this.dsl = dsl;
	}

	@Override
	public void replaceActive(String normalizedEmail, EmailChallengePurpose purpose, Instant now) {
		dsl.execute(REPLACE_ACTIVE_SQL, utc(now), normalizedEmail, purpose.name());
	}

	@Override
	public void insert(EmailChallenge challenge) {
		dsl.execute(
			INSERT_SQL,
			challenge.id(), challenge.purpose().name(), challenge.emailNormalized(), challenge.codeHash(),
			utc(challenge.expiresAt()), challenge.attemptCount(), challenge.maxAttempts(),
			utcNullable(challenge.consumedAt()), utcNullable(challenge.invalidatedAt()),
			challenge.invalidationReason() == null ? null : challenge.invalidationReason().name(),
			utc(challenge.createdAt()));
	}

	@Override
	public Optional<EmailChallenge> findLatestForUpdate(
		String normalizedEmail,
		EmailChallengePurpose purpose) {
		Record record = dsl.resultQuery(FIND_LATEST_FOR_UPDATE_SQL, normalizedEmail, purpose.name())
			.fetchOne();
		return record == null ? Optional.empty() : Optional.of(toDomain(record));
	}

	@Override
	public void markExpired(UUID challengeId, Instant now) {
		dsl.execute(MARK_EXPIRED_SQL, utc(now), challengeId, utc(now));
	}

	@Override
	public boolean consume(UUID challengeId, Instant now) {
		return dsl.execute(CONSUME_SQL, utc(now), challengeId, utc(now)) == 1;
	}

	@Override
	public boolean recordFailedAttempt(UUID challengeId, Instant now) {
		return dsl.execute(RECORD_FAILED_ATTEMPT_SQL, utc(now), challengeId, utc(now)) == 1;
	}

	private static EmailChallenge toDomain(Record record) {
		return EmailChallenge.restore(
			record.get("id", UUID.class),
			EmailChallengePurpose.valueOf(record.get("purpose", String.class)),
			record.get("email_normalized", String.class),
			record.get("code_hash", String.class),
			instant(record.get("expires_at", OffsetDateTime.class)),
			record.get("attempt_count", Integer.class),
			record.get("max_attempts", Integer.class),
			instantNullable(record.get("consumed_at", OffsetDateTime.class)),
			instantNullable(record.get("invalidated_at", OffsetDateTime.class)),
			reason(record.get("invalidation_reason", String.class)),
			instant(record.get("created_at", OffsetDateTime.class)));
	}

	private static EmailChallengeInvalidationReason reason(String value) {
		return value == null ? null : EmailChallengeInvalidationReason.valueOf(value);
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new AuthInfrastructureException("认证时间不能为空。");
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static OffsetDateTime utcNullable(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		if (value == null) {
			throw new AuthInfrastructureException("认证时间读取失败。");
		}
		return value.toInstant();
	}

	private static Instant instantNullable(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
