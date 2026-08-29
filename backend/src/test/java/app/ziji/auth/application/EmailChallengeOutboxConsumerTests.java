package app.ziji.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 邮件 outbox 消费回归：验证事件解密后才投递，并以 receipt 终态保证重复消费不重发。 */
class EmailChallengeOutboxConsumerTests {

	private static final Instant NOW = Instant.parse("2026-08-29T06:00:00Z");

	@Test
	void decryptsChallengeAndSendsItBeforeMarkingReceiptSucceeded() {
		UUID eventId = UUID.randomUUID();
		UUID challengeId = UUID.randomUUID();
		FakeStore store = new FakeStore(event(eventId, challengeId));
		FakeDelivery delivery = new FakeDelivery();
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, delivery, (id, purpose, envelope) -> "123456",
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("SUCCEEDED", store.status);
		assertEquals("user@example.com", delivery.message.email());
		assertEquals("123456", delivery.message.verificationCode());
		assertEquals(eventId, delivery.message.eventId());
	}

	@Test
	void malformedPayloadIsFinalizedWithoutCallingDelivery() {
		FakeStore store = new FakeStore(new EmailOutboxEvent(
			UUID.randomUUID(), UUID.randomUUID(), "EmailChallenge", "EmailChallengeIssued", 1,
			Map.of("schemaVersion", 1), NOW.minusSeconds(1), 1, true));
		FakeDelivery delivery = new FakeDelivery();
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, delivery, (id, purpose, envelope) -> "123456",
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("FAILED_FINAL", store.status);
		assertEquals(0, delivery.sendCount);
	}

	@Test
	void expirationBeforeDeliveryIsFinalizedWithoutSending() {
		UUID eventId = UUID.randomUUID();
		UUID challengeId = UUID.randomUUID();
		FakeStore store = new FakeStore(event(eventId, challengeId, NOW.minusSeconds(1)));
		FakeDelivery delivery = new FakeDelivery();
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, delivery, (id, purpose, envelope) -> "123456",
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("FAILED_FINAL", store.status);
		assertEquals("EMAIL_CHALLENGE_EXPIRED", store.errorCode);
		assertEquals(0, delivery.sendCount);
	}

	@Test
	void deliveryFailureIsRetryableWithBackoff() {
		FakeStore store = new FakeStore(event(UUID.randomUUID(), UUID.randomUUID()));
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, message -> {
				throw new IllegalStateException("smtp unavailable");
			}, (id, purpose, envelope) -> "123456",
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("FAILED_RETRYABLE", store.status);
		assertEquals("EMAIL_DELIVERY_FAILED", store.errorCode);
	}

	@Test
	void decryptFailureIsFinalizedWithoutCallingDelivery() {
		FakeStore store = new FakeStore(event(UUID.randomUUID(), UUID.randomUUID()));
		FakeDelivery delivery = new FakeDelivery();
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, delivery, (id, purpose, envelope) -> {
				throw new IllegalStateException("envelope damaged");
			}, new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("FAILED_FINAL", store.status);
		assertEquals("EMAIL_ENVELOPE_DECRYPT_FAILED", store.errorCode);
		assertEquals(0, delivery.sendCount);
	}

	@Test
	void nonDigitPlaintextIsFinalizedWithoutCallingDelivery() {
		FakeStore store = new FakeStore(event(UUID.randomUUID(), UUID.randomUUID()));
		FakeDelivery delivery = new FakeDelivery();
		EmailChallengeOutboxConsumer consumer = new EmailChallengeOutboxConsumer(
			store, delivery, (id, purpose, envelope) -> "12a456",
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals("FAILED_FINAL", store.status);
		assertEquals("EMAIL_ENVELOPE_DECRYPT_FAILED", store.errorCode);
		assertEquals(0, delivery.sendCount);
	}

	private static EmailOutboxEvent event(UUID eventId, UUID challengeId) {
		return event(eventId, challengeId, NOW.plusSeconds(600));
	}

	private static EmailOutboxEvent event(UUID eventId, UUID challengeId, Instant expiresAt) {
		Map<String, Object> envelope = new HashMap<>();
		envelope.put("algorithm", "A256GCM");
		envelope.put("keyEncryptionAlgorithm", "A256GCM");
		envelope.put("keyVersion", 1);
		envelope.put("nonce", "nonce");
		envelope.put("ciphertext", "ciphertext");
		envelope.put("wrappedDataKey", "wrapped-key");
		envelope.put("wrappedDataKeyNonce", "wrapped-nonce");
		return new EmailOutboxEvent(
			eventId, challengeId, "EmailChallenge", "EmailChallengeIssued", 1,
			Map.of(
				"schemaVersion", 1,
				"challengeId", challengeId.toString(),
				"purpose", "REGISTER",
				"email", "user@example.com",
				"expiresAt", expiresAt.toString(),
				"verificationCode", envelope),
			NOW.minusSeconds(1), 1, true);
	}

	private static final class FakeStore implements EmailOutboxStore {
		private final EmailOutboxEvent event;
		private final EmailOutboxClaim claim;
		private String status = "PENDING";
		private String errorCode;

		private FakeStore(EmailOutboxEvent event) {
			this.event = event;
			this.claim = new EmailOutboxClaim("EMAIL", event, UUID.randomUUID());
		}

		@Override
		public Optional<EmailOutboxClaim> claimNext(String consumerName, Instant now, Instant leaseUntil) {
			return "PENDING".equals(status) ? Optional.of(claim) : Optional.empty();
		}

		@Override
		public void markSucceeded(EmailOutboxClaim claim, Instant completedAt) {
			status = "SUCCEEDED";
		}

		@Override
		public void markRetryable(
			EmailOutboxClaim claim, Instant failedAt, Instant nextAttemptAt, String errorCode) {
			status = "FAILED_RETRYABLE";
			this.errorCode = errorCode;
		}

		@Override
		public void markFinal(EmailOutboxClaim claim, Instant failedAt, String errorCode) {
			status = "FAILED_FINAL";
			this.errorCode = errorCode;
		}
	}

	private static final class FakeDelivery implements EmailDelivery {
		private EmailChallengeEmail message;
		private int sendCount;

		@Override
		public void send(EmailChallengeEmail message) {
			this.message = message;
			sendCount++;
		}
	}

	private static final class DirectTransactionRunner implements app.ziji.shared.application.TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}
}
