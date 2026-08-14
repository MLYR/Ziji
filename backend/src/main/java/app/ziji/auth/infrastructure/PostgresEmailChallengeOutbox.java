package app.ziji.auth.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import app.ziji.auth.application.EmailChallengeIssuedEvent;
import app.ziji.auth.application.EmailChallengeOutbox;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** 将版本化加密事件写入既有 outbox_events 表，调用方事务负责原子提交。 */
@Repository
public final class PostgresEmailChallengeOutbox implements EmailChallengeOutbox {

	private static final String INSERT_SQL = """
		INSERT INTO outbox_events
			(id, aggregate_type, aggregate_id, event_type, payload, payload_version,
			 occurred_at, published_at, attempt_count, next_attempt_at)
		VALUES (?, 'EmailChallenge', ?, 'EmailChallengeIssued', CAST(? AS jsonb), 1, ?, NULL, 0, ?)
		""";

	private final DSLContext dsl;
	private final ObjectMapper objectMapper;

	public PostgresEmailChallengeOutbox(DSLContext dsl, ObjectMapper objectMapper) {
		this.dsl = dsl;
		this.objectMapper = objectMapper;
	}

	@Override
	public void append(EmailChallengeIssuedEvent event) {
		try {
			String payload = objectMapper.writeValueAsString(new Payload(event));
			dsl.execute(
				INSERT_SQL,
				event.eventId(), event.challengeId(), payload, utc(event.occurredAt()), utc(event.occurredAt()));
		} catch (JacksonException exception) {
			throw new AuthInfrastructureException("认证 outbox 载荷序列化失败。", exception);
		}
	}

	private static OffsetDateTime utc(java.time.Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	/** 显式建模载荷，确保验证码只有 encryptedCode 信封字段而无明文字段。 */
	private static final class Payload {
		private final int schemaVersion = 1;
		private final String challengeId;
		private final String purpose;
		private final String email;
		private final String expiresAt;
		private final EncryptedCodeEnvelope verificationCode;

		private Payload(EmailChallengeIssuedEvent event) {
			this.challengeId = event.challengeId().toString();
			this.purpose = event.purpose().name();
			this.email = event.normalizedEmail();
			this.expiresAt = event.expiresAt().toString();
			this.verificationCode = event.encryptedCode();
		}

		public int getSchemaVersion() {
			return schemaVersion;
		}

		public String getChallengeId() {
			return challengeId;
		}

		public String getPurpose() {
			return purpose;
		}

		public String getEmail() {
			return email;
		}

		public String getExpiresAt() {
			return expiresAt;
		}

		public EncryptedCodeEnvelope getVerificationCode() {
			return verificationCode;
		}
	}
}
