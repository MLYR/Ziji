package app.ziji.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.EmailChallengeStatus;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** application 编排测试：事务、统一结果、挑战替换和一次性消费。 */
class EmailChallengeApplicationServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final SourceAddress SOURCE = SourceAddress.parseLiteral("192.0.2.10");

	@Test
	void issueUsesNormalizedEmailAndStoresOnlyHashAndEncryptedOutboxCode() {
		Fixture fixture = fixture(RateLimitDecision.permitted());

		EmailChallengeIssueResult result = fixture.service.issue(
			new EmailChallengeIssueCommand(EmailChallengePurpose.REGISTER,
				" User@Example.com ", "device-1", SOURCE));

		assertTrue(result.accepted());
		assertEquals(600, result.expiresInSeconds());
		assertEquals("user@example.com", fixture.store.latest(
			EmailChallengePurpose.REGISTER, "user@example.com").orElseThrow().emailNormalized());
		assertNotEquals("123456", fixture.store.latest(
			EmailChallengePurpose.REGISTER, "user@example.com").orElseThrow().codeHash());
		assertEquals(1, fixture.outbox.events.size());
		assertNotEquals("123456", fixture.outbox.events.get(0).encryptedCode().ciphertext());
	}

	@Test
	void rateLimitedIssueCommitsDecisionWithoutCreatingChallengeOrOutbox() {
		Fixture fixture = fixture(RateLimitDecision.denied(37));

		EmailChallengeIssueResult result = fixture.service.issue(
			new EmailChallengeIssueCommand(EmailChallengePurpose.REGISTER,
				"user@example.com", null, SOURCE));

		assertFalse(result.accepted());
		assertEquals(37, result.retryAfterSeconds());
		assertEquals(0, fixture.store.insertCount);
		assertTrue(fixture.outbox.events.isEmpty());
	}

	@Test
	void registerAndResetPasswordChallengesAreIsolated() {
		Fixture fixture = fixture(RateLimitDecision.permitted());

		fixture.service.issue(command(EmailChallengePurpose.REGISTER));
		fixture.service.issue(command(EmailChallengePurpose.RESET_PASSWORD));

		assertEquals(EmailChallengeStatus.ACTIVE,
			fixture.store.latest(EmailChallengePurpose.REGISTER, "user@example.com")
				.orElseThrow().status());
		assertEquals(EmailChallengeStatus.ACTIVE,
			fixture.store.latest(EmailChallengePurpose.RESET_PASSWORD, "user@example.com")
				.orElseThrow().status());
	}

	@Test
	void correctVerificationConsumesOnceAndWrongAttemptsStopAtFive() {
		Fixture fixture = fixture(RateLimitDecision.permitted());
		fixture.service.issue(command(EmailChallengePurpose.REGISTER));

		assertEquals(EmailChallengeVerificationResult.VALID,
			fixture.service.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, "USER@example.com", "123456")));
		assertEquals(EmailChallengeVerificationResult.INVALID,
			fixture.service.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, "USER@example.com", "123456")));

		Fixture wrongFixture = fixture(RateLimitDecision.permitted());
		wrongFixture.service.issue(command(EmailChallengePurpose.REGISTER));
		for (int attempt = 0; attempt < 5; attempt++) {
			assertEquals(EmailChallengeVerificationResult.INVALID,
				wrongFixture.service.verify(new EmailChallengeVerificationCommand(
					EmailChallengePurpose.REGISTER, "user@example.com", "999999")));
		}
		assertEquals(EmailChallengeStatus.MAX_ATTEMPTS,
			wrongFixture.store.latest(EmailChallengePurpose.REGISTER, "user@example.com")
				.orElseThrow().status());
	}

	@Test
	void injectedClockExpiresChallengeWithoutUsingSystemTime() {
		Fixture fixture = fixture(RateLimitDecision.permitted(), NOW);
		fixture.service.issue(command(EmailChallengePurpose.REGISTER));
		fixture.service = fixture.serviceAt(NOW.plusSeconds(600));

		assertEquals(EmailChallengeVerificationResult.INVALID,
			fixture.service.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, "user@example.com", "123456")));
		assertEquals(EmailChallengeStatus.EXPIRED,
			fixture.store.latest(EmailChallengePurpose.REGISTER, "user@example.com")
				.orElseThrow().status());
	}

	private static EmailChallengeIssueCommand command(EmailChallengePurpose purpose) {
		return new EmailChallengeIssueCommand(purpose, "user@example.com", null, SOURCE);
	}

	private static Fixture fixture(RateLimitDecision decision) {
		return fixture(decision, NOW);
	}

	private static Fixture fixture(RateLimitDecision decision, Instant now) {
		FakeChallengeStore store = new FakeChallengeStore();
		FakeOutbox outbox = new FakeOutbox();
		FakeRateLimitStore rateLimits = new FakeRateLimitStore(decision);
		EmailChallengeApplicationService service = newService(store, rateLimits, outbox, now);
		return new Fixture(store, outbox, rateLimits, service, now);
	}

	private static EmailChallengeApplicationService newService(
		FakeChallengeStore store,
		FakeRateLimitStore rateLimits,
		FakeOutbox outbox,
		Instant now) {
		return new EmailChallengeApplicationService(
			new DirectTransactionRunner(), store, rateLimits, () -> "123456",
			new FakeCodeHasher(), (challengeId, purpose, code) -> new EncryptedCodeEnvelope(
				"A256GCM", "A256GCM", 1, "nonce", "ciphertext", "wrapped-key", "wrapped-nonce"),
			outbox, Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
	}

	private static final class Fixture {
		private final FakeChallengeStore store;
		private final FakeOutbox outbox;
		private final FakeRateLimitStore rateLimits;
		private EmailChallengeApplicationService service;
		private final Instant now;

		private Fixture(
			FakeChallengeStore store,
			FakeOutbox outbox,
			FakeRateLimitStore rateLimits,
			EmailChallengeApplicationService service,
			Instant now) {
			this.store = store;
			this.outbox = outbox;
			this.rateLimits = rateLimits;
			this.service = service;
			this.now = now;
		}

		private EmailChallengeApplicationService serviceAt(Instant instant) {
			return newService(store, rateLimits, outbox, instant);
		}
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class FakeCodeHasher implements ChallengeCodeHasher {
		@Override
		public String hash(EmailChallengePurpose purpose, String normalizedEmail, String code) {
			return "v1:2:opaque-hash";
		}

		@Override
		public boolean matches(String storedHash, EmailChallengePurpose purpose,
			String normalizedEmail, String code) {
			return "123456".equals(code);
		}
	}

	private static final class FakeRateLimitStore implements AuthRateLimitStore {
		private final RateLimitDecision decision;
		private int calls;

		private FakeRateLimitStore(RateLimitDecision decision) {
			this.decision = decision;
		}

		@Override
		public RateLimitDecision consume(EmailChallengePurpose purpose,
			AuthRateLimitSubjects subjects, Instant now) {
			calls++;
			return decision;
		}
	}

	private static final class FakeChallengeStore implements EmailChallengeStore {
		private final Map<String, EmailChallenge> challenges = new HashMap<>();
		private int insertCount;

		@Override
		public void replaceActive(String normalizedEmail, EmailChallengePurpose purpose, Instant now) {
			String key = key(purpose, normalizedEmail);
			EmailChallenge challenge = challenges.get(key);
			if (challenge != null && challenge.status() == EmailChallengeStatus.ACTIVE) {
				challenges.put(key, challenge.replaceAt(now));
			}
		}

		@Override
		public void insert(EmailChallenge challenge) {
			insertCount++;
			challenges.put(key(challenge.purpose(), challenge.emailNormalized()), challenge);
		}

		@Override
		public Optional<EmailChallenge> findLatestForUpdate(
			String normalizedEmail, EmailChallengePurpose purpose) {
			return Optional.ofNullable(challenges.get(key(purpose, normalizedEmail)));
		}

		@Override
		public void markExpired(UUID challengeId, Instant now) {
			update(challengeId, challenge -> challenge.expireAt(now));
		}

		@Override
		public boolean consume(UUID challengeId, Instant now) {
			return update(challengeId, challenge -> challenge.consumeAt(now));
		}

		@Override
		public boolean recordFailedAttempt(UUID challengeId, Instant now) {
			return update(challengeId, challenge -> challenge.recordFailedAttemptAt(now));
		}

		private boolean update(UUID id, java.util.function.UnaryOperator<EmailChallenge> transition) {
			for (Map.Entry<String, EmailChallenge> entry : challenges.entrySet()) {
				if (entry.getValue().id().equals(id)) {
					entry.setValue(transition.apply(entry.getValue()));
					return true;
				}
			}
			return false;
		}

		private Optional<EmailChallenge> latest(EmailChallengePurpose purpose, String email) {
			return Optional.ofNullable(challenges.get(key(purpose, email)));
		}

		private static String key(EmailChallengePurpose purpose, String email) {
			return purpose.name() + ":" + email;
		}
	}

	private static final class FakeOutbox implements EmailChallengeOutbox {
		private final List<EmailChallengeIssuedEvent> events = new java.util.ArrayList<>();

		@Override
		public void append(EmailChallengeIssuedEvent event) {
			events.add(event);
		}
	}
}
