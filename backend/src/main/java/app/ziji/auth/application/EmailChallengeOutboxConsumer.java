package app.ziji.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.shared.application.TransactionRunner;

/** 消费 EMAIL 持久订阅命中的 EmailChallengeIssued 事件：解密后投递，再用 receipt 终态保证不重复发送。 */
public final class EmailChallengeOutboxConsumer {

	public static final String CONSUMER_NAME = "EMAIL";
	private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
	private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

	private final EmailOutboxStore outbox;
	private final EmailDelivery delivery;
	private final EnvelopeDecryptor decryptor;
	private final TransactionRunner transactions;
	private final Clock clock;

	public EmailChallengeOutboxConsumer(
		EmailOutboxStore outbox,
		EmailDelivery delivery,
		EnvelopeDecryptor decryptor,
		TransactionRunner transactions,
		Clock clock) {
		if (outbox == null || delivery == null || decryptor == null || transactions == null || clock == null) {
			throw new IllegalArgumentException("邮件消费者依赖不能为空。");
		}
		this.outbox = outbox;
		this.delivery = delivery;
		this.decryptor = decryptor;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** 处理一条当前 EMAIL subscription 可领取事件；没有可领取 receipt 时返回 false。 */
	public boolean consumeNext() {
		Instant now = clock.instant();
		Optional<EmailOutboxClaim> claimed = transactions.required(
			() -> outbox.claimNext(CONSUMER_NAME, now, now.plus(CLAIM_LEASE)));
		if (claimed.isEmpty()) {
			return false;
		}
		EmailOutboxClaim claim = claimed.get();
		try {
			EmailChallengeEmail email = project(claim.event());
			// SMTP 投递保持事务外执行；只有投递成功才允许写入 SUCCEEDED receipt。
			delivery.send(email);
			transactions.required(() -> outbox.markSucceeded(claim, clock.instant()));
		} catch (FinalOutboxFailure failure) {
			transactions.required(() -> outbox.markFinal(claim, clock.instant(), failure.errorCode));
		} catch (RuntimeException failure) {
			transactions.required(() -> outbox.markRetryable(
				claim, clock.instant(), clock.instant().plus(RETRY_BACKOFF), "EMAIL_DELIVERY_FAILED"));
		}
		return true;
	}

	/** 排空当前全部可领取事件；调度器和启动恢复复用同一入口，不创建额外线程。 */
	public int consumeAvailable() {
		int consumed = 0;
		while (consumeNext()) {
			consumed++;
		}
		return consumed;
	}

	private EmailChallengeEmail project(EmailOutboxEvent event) {
		if (!event.payloadJsonValid()) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID_JSON");
		}
		if (!"EmailChallenge".equals(event.aggregateType())
			|| !"EmailChallengeIssued".equals(event.eventType())) {
			throw finalFailure("OUTBOX_EVENT_UNSUPPORTED");
		}
		if (event.payloadVersion() != 1 || positiveInt(event.payload(), "schemaVersion") != 1) {
			throw finalFailure("OUTBOX_SCHEMA_INVALID");
		}
		UUID challengeId = uuid(event.payload(), "challengeId");
		if (!event.aggregateId().equals(challengeId)) {
			throw finalFailure("OUTBOX_TARGET_MISMATCH");
		}
		EmailChallengePurpose purpose = purpose(event.payload());
		String email = text(event.payload(), "email");
		Instant expiresAt = instant(event.payload(), "expiresAt");
		// 已过期验证码不应再投递；该终态同时允许事件清理。
		if (!clock.instant().isBefore(expiresAt)) {
			throw finalFailure("EMAIL_CHALLENGE_EXPIRED");
		}
		EncryptedCodeEnvelope envelope = envelope(event.payload());
		String code;
		try {
			code = decryptor.decrypt(challengeId, purpose, envelope);
		} catch (RuntimeException failure) {
			// 密钥轮换或损坏载荷不可通过重试解决，进入终态避免无限重放。
			throw finalFailure("EMAIL_ENVELOPE_DECRYPT_FAILED");
		}
		if (code == null || !code.matches("[0-9]{6}")) {
			throw finalFailure("EMAIL_ENVELOPE_DECRYPT_FAILED");
		}
		return new EmailChallengeEmail(
			event.id(), challengeId, purpose, email, code, expiresAt);
	}

	private static EncryptedCodeEnvelope envelope(Map<String, Object> payload) {
		Object value = payload.get("verificationCode");
		if (!(value instanceof Map<?, ?> raw)) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> envelope = (Map<String, Object>) raw;
		return new EncryptedCodeEnvelope(
			text(envelope, "algorithm"),
			text(envelope, "keyEncryptionAlgorithm"),
			positiveInt(envelope, "keyVersion"),
			text(envelope, "nonce"),
			text(envelope, "ciphertext"),
			text(envelope, "wrappedDataKey"),
			text(envelope, "wrappedDataKeyNonce"));
	}

	private static UUID uuid(Map<String, Object> payload, String key) {
		String value = text(payload, key);
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
	}

	private static EmailChallengePurpose purpose(Map<String, Object> payload) {
		try {
			return EmailChallengePurpose.valueOf(text(payload, "purpose"));
		} catch (IllegalArgumentException exception) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
	}

	private static Instant instant(Map<String, Object> payload, String key) {
		String value = text(payload, key);
		try {
			return Instant.parse(value);
		} catch (java.time.format.DateTimeParseException exception) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
	}

	private static int positiveInt(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Integer integer && integer > 0) {
			return integer;
		}
		if (value instanceof Long longValue && longValue >= 1 && longValue <= Integer.MAX_VALUE) {
			return longValue.intValue();
		}
		if (value instanceof Short shortValue && shortValue > 0) {
			return shortValue.intValue();
		}
		if (value instanceof Byte byteValue && byteValue > 0) {
			return byteValue.intValue();
		}
		throw finalFailure("OUTBOX_PAYLOAD_INVALID");
	}

	private static String text(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw finalFailure("OUTBOX_PAYLOAD_INVALID");
	}

	private static FinalOutboxFailure finalFailure(String errorCode) {
		return new FinalOutboxFailure(errorCode);
	}

	private static final class FinalOutboxFailure extends RuntimeException {
		private final String errorCode;

		private FinalOutboxFailure(String errorCode) {
			this.errorCode = errorCode;
		}
	}
}
