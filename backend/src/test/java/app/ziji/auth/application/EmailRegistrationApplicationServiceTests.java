package app.ziji.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.EmailChallengeStatus;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserEmailAlreadyExistsException;
import app.ziji.user.application.UserRegistrationCommand;
import app.ziji.user.application.UserRegistrationPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册应用编排测试：先校验资料，再在同一事务内消费 REGISTER 挑战和创建用户。 */
class EmailRegistrationApplicationServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final String EMAIL = "user@example.com";

	@Test
	void validatesPasswordBoundariesBeforeConsumingChallenge() {
		Fixture fixture = fixture(NOW);

		assertThrows(RegistrationValidationException.class,
			() -> fixture.service.register(command("x".repeat(9), "CNY", "Asia/Shanghai")));
		assertThrows(RegistrationValidationException.class,
			() -> fixture.service.register(command("x".repeat(129), "CNY", "Asia/Shanghai")));
		assertEquals(EmailChallengeStatus.ACTIVE, fixture.store.latest(EMAIL, EmailChallengePurpose.REGISTER)
			.orElseThrow().status());
		assertEquals(0, fixture.userPort.calls);
		assertEquals(0, fixture.passwordHasher.hashCalls);

		EmailRegistrationResult result = fixture.service.register(
			command("x".repeat(10), "CNY", "Asia/Shanghai"));
		assertEquals(EMAIL, result.email());
		assertEquals(1, fixture.userPort.calls);
		assertEquals(1, fixture.passwordHasher.hashCalls);

		Fixture maximum = fixture(NOW);
		maximum.service.register(command("x".repeat(128), "CNY", "Asia/Shanghai"));
		assertEquals(1, maximum.userPort.calls);
		assertEquals(1, maximum.passwordHasher.hashCalls);
	}

	@Test
	void rejectsInvalidRegistrationFieldsBeforeConsumingChallengeOrHashing() {
		Fixture invalidEmail = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> invalidEmail.service.register(
			new EmailRegistrationCommand("invalid", "123456", "same-password", "昵称", "Asia/Shanghai", "CNY", "zh-CN")));
		assertEquals(0, invalidEmail.passwordHasher.hashCalls);

		Fixture invalidCode = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> invalidCode.service.register(
			new EmailRegistrationCommand(EMAIL, "12345", "same-password", "昵称", "Asia/Shanghai", "CNY", "zh-CN")));
		assertEquals(0, invalidCode.passwordHasher.hashCalls);

		Fixture emptyNickname = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> emptyNickname.service.register(
			new EmailRegistrationCommand(EMAIL, "123456", "same-password", "", "Asia/Shanghai", "CNY", "zh-CN")));
		assertEquals(EmailChallengeStatus.ACTIVE, emptyNickname.store.latest(EMAIL, EmailChallengePurpose.REGISTER)
			.orElseThrow().status());
		assertEquals(0, emptyNickname.passwordHasher.hashCalls);

		Fixture longNickname = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> longNickname.service.register(
			new EmailRegistrationCommand(EMAIL, "123456", "same-password", "x".repeat(101), "Asia/Shanghai", "CNY", "zh-CN")));
		assertEquals(0, longNickname.passwordHasher.hashCalls);

		Fixture shortLocale = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> shortLocale.service.register(
			new EmailRegistrationCommand(EMAIL, "123456", "same-password", "昵称", "Asia/Shanghai", "CNY", "z")));
		assertEquals(0, shortLocale.passwordHasher.hashCalls);

		Fixture longLocale = fixture(NOW);
		assertThrows(RegistrationValidationException.class, () -> longLocale.service.register(
			new EmailRegistrationCommand(EMAIL, "123456", "same-password", "昵称", "Asia/Shanghai", "CNY", "x".repeat(17))));
		assertEquals(0, longLocale.passwordHasher.hashCalls);
	}

	@Test
	void acceptsAllV1CurrenciesWithValidIanaTimezone() {
		for (String currency : new String[] {"CNY", "USD", "HKD", "JPY", "EUR"}) {
			Fixture fixture = fixture(NOW);

			EmailRegistrationResult result = fixture.service.register(
				command("same-password", currency, "Asia/Shanghai"));

			assertEquals(currency, result.baseCurrency());
			assertEquals("Asia/Shanghai", result.timezone());
		}
	}

	@Test
	void rejectsInvalidTimezoneBeforeConsumingChallenge() {
		Fixture fixture = fixture(NOW);

		assertThrows(RegistrationValidationException.class,
			() -> fixture.service.register(command("same-password", "CNY", "Not/A/Timezone")));
		assertEquals(EmailChallengeStatus.ACTIVE, fixture.store.latest(EMAIL, EmailChallengePurpose.REGISTER)
			.orElseThrow().status());
		assertEquals(0, fixture.userPort.calls);
		assertEquals(0, fixture.passwordHasher.hashCalls);
	}

	@Test
	void invalidChallengesNeverHashPasswordsOrCreateUsers() {
		Fixture wrong = fixture(NOW);
		assertThrows(RegistrationValidationException.class,
			() -> wrong.service.register(commandWithCode("999999")));
		assertEquals(0, wrong.userPort.calls);
		assertEquals(0, wrong.passwordHasher.hashCalls);

		Fixture expired = fixture(NOW.minusSeconds(600));
		assertThrows(RegistrationValidationException.class,
			() -> expired.service.register(command("same-password", "CNY", "Asia/Shanghai")));
		assertEquals(0, expired.userPort.calls);
		assertEquals(0, expired.passwordHasher.hashCalls);

		Fixture repeated = fixture(NOW);
		repeated.store.challenge = repeated.store.challenge.consumeAt(NOW);
		assertThrows(RegistrationValidationException.class,
			() -> repeated.service.register(command("same-password", "CNY", "Asia/Shanghai")));
		assertEquals(0, repeated.userPort.calls);
		assertEquals(0, repeated.passwordHasher.hashCalls);

		Fixture reset = fixture(NOW, EmailChallengePurpose.RESET_PASSWORD);
		assertThrows(RegistrationValidationException.class,
			() -> reset.service.register(command("same-password", "CNY", "Asia/Shanghai")));
		assertEquals(0, reset.userPort.calls);
		assertEquals(0, reset.passwordHasher.hashCalls);

		Fixture absent = fixture(NOW);
		absent.store.challenge = null;
		assertThrows(RegistrationValidationException.class,
			() -> absent.service.register(command("same-password", "CNY", "Asia/Shanghai")));
		assertEquals(0, absent.userPort.calls);
		assertEquals(0, absent.passwordHasher.hashCalls);
	}

	@Test
	void mapsOnlyExplicitEmailDuplicateToRegistrationConflict() {
		Fixture fixture = fixture(NOW);
		fixture.userPort.duplicate = true;

		assertThrows(EmailAlreadyRegisteredException.class,
			() -> fixture.service.register(command("same-password", "CNY", "Asia/Shanghai")));
	}

	@Test
	void registrationResultContainsNoPasswordOrHash() {
		Fixture fixture = fixture(NOW);

		EmailRegistrationResult result = fixture.service.register(
			command("same-password", "CNY", "Asia/Shanghai"));

		assertFalse(result.toString().contains("same-password"));
		assertTrue(java.util.Arrays.stream(EmailRegistrationResult.class.getDeclaredFields())
			.noneMatch(field -> field.getName().toLowerCase(java.util.Locale.ROOT).contains("password")
				|| field.getName().toLowerCase(java.util.Locale.ROOT).contains("hash")));
		assertNotEquals("same-password", result.email());
	}

	private static EmailRegistrationCommand command(String password, String currency, String timezone) {
		return new EmailRegistrationCommand(
			EMAIL, "123456", password, "昵称", timezone, currency, "zh-CN");
	}

	private static EmailRegistrationCommand commandWithCode(String code) {
		return new EmailRegistrationCommand(
			EMAIL, code, "same-password", "昵称", "Asia/Shanghai", "CNY", "zh-CN");
	}

	private static Fixture fixture(Instant now) {
		return fixture(now, EmailChallengePurpose.REGISTER);
	}

	private static Fixture fixture(Instant now, EmailChallengePurpose purpose) {
		FakeChallengeStore store = new FakeChallengeStore();
		store.challenge = EmailChallenge.issue(UUID.randomUUID(), purpose, EMAIL, "challenge-hash", now);
		DirectTransactionRunner transactions = new DirectTransactionRunner();
		EmailChallengeApplicationService challengeService = new EmailChallengeApplicationService(
			transactions, store, (challengePurpose, subjects, instant) -> RateLimitDecision.permitted(),
			() -> "123456", new FakeChallengeHasher(), (challengeId, challengePurpose, code) -> null,
			event -> { }, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
		FakePasswordHasher passwordHasher = new FakePasswordHasher();
		FakeUserRegistrationPort userPort = new FakeUserRegistrationPort();
		EmailRegistrationApplicationService service = new EmailRegistrationApplicationService(
			transactions, challengeService, passwordHasher, userPort,
			Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
		return new Fixture(store, passwordHasher, userPort, service);
	}

	private record Fixture(
		FakeChallengeStore store,
		FakePasswordHasher passwordHasher,
		FakeUserRegistrationPort userPort,
		EmailRegistrationApplicationService service) {
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

	private static final class FakeChallengeHasher implements ChallengeCodeHasher {
		@Override
		public String hash(EmailChallengePurpose purpose, String normalizedEmail, String code) {
			return "challenge-hash";
		}

		@Override
		public boolean matches(String storedHash, EmailChallengePurpose purpose,
			String normalizedEmail, String code) {
			return "123456".equals(code);
		}
	}

	private static final class FakePasswordHasher implements PasswordHasher {
		private int hashCalls;

		@Override
		public String hash(String password) {
			hashCalls++;
			return "$argon2id$fake-" + password.length();
		}

		@Override
		public boolean matches(String password, String encodedHash) {
			return encodedHash.equals("$argon2id$fake-" + password.length());
		}
	}

	private static final class FakeChallengeStore implements EmailChallengeStore {
		private EmailChallenge challenge;

		@Override
		public void replaceActive(String normalizedEmail, EmailChallengePurpose purpose, Instant now) {
		}

		@Override
		public void insert(EmailChallenge value) {
			challenge = value;
		}

		@Override
		public Optional<EmailChallenge> findLatestForUpdate(String normalizedEmail, EmailChallengePurpose purpose) {
			return challenge != null && challenge.purpose() == purpose
				&& challenge.emailNormalized().equals(normalizedEmail) ? Optional.of(challenge) : Optional.empty();
		}

		@Override
		public void markExpired(UUID challengeId, Instant now) {
			challenge = challenge.expireAt(now);
		}

		@Override
		public boolean consume(UUID challengeId, Instant now) {
			if (challenge == null || !challenge.id().equals(challengeId)) {
				return false;
			}
			challenge = challenge.consumeAt(now);
			return true;
		}

		@Override
		public boolean recordFailedAttempt(UUID challengeId, Instant now) {
			if (challenge == null || !challenge.id().equals(challengeId)) {
				return false;
			}
			challenge = challenge.recordFailedAttemptAt(now);
			return true;
		}

		private Optional<EmailChallenge> latest(String email, EmailChallengePurpose purpose) {
			return findLatestForUpdate(email, purpose);
		}
	}

	private static final class FakeUserRegistrationPort implements UserRegistrationPort {
		private int calls;
		private boolean duplicate;

		@Override
		public void register(UserRegistrationCommand command) {
			calls++;
			if (duplicate) {
				throw new UserEmailAlreadyExistsException();
			}
		}
	}
}
