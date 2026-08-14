package app.ziji;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeOutbox;
import app.ziji.auth.application.EmailAlreadyRegisteredException;
import app.ziji.auth.application.EmailRegistrationApplicationService;
import app.ziji.auth.application.EmailRegistrationCommand;
import app.ziji.auth.application.EnvelopeEncryptor;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordHashingException;
import app.ziji.auth.application.RegistrationValidationException;
import app.ziji.auth.application.VerificationCodeGenerator;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.infrastructure.PostgresAuthRateLimitStore;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserPersistenceException;
import app.ziji.user.application.UserRegistrationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL 注册验收基线：验证码消费、users 写入和唯一约束必须在同一事务内提交。 */
@SpringBootTest
@ActiveProfiles("test")
class EmailRegistrationPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final String PASSWORD = "same-password";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

	@Autowired
	private PostgresAuthRateLimitStore rateLimitStore;

	@Autowired
	private VerificationCodeGenerator codeGenerator;

	@Autowired
	private ChallengeCodeHasher codeHasher;

	@Autowired
	private EnvelopeEncryptor envelopeEncryptor;

	@Autowired
	private EmailChallengeOutbox outbox;

	@Autowired
	private PasswordHasher passwordHasher;

	@Autowired
	private UserRegistrationPort userRegistrationPort;

	@BeforeEach
	void clearRegistrationFacts() {
		// 测试邮箱独立清理，避免影响其他认证/用户集成测试写入的事实。
		jdbc.update("DELETE FROM email_challenges WHERE email_normalized LIKE 'registration-%@example.test'");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'registration-%@example.test'");
	}

	@Test
	void validRegisterChallengeCreatesActiveVerifiedUserAndConsumesChallenge() {
		String email = "registration-success@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, email, "123456", NOW);

		var result = registrationService(NOW, userRegistrationPort).register(command(email, "123456"));

		assertEquals(email, result.email());
		var user = jdbc.queryForMap("""
			SELECT email_verified_at, password_hash, password_hash_version, nickname, timezone,
				base_currency, locale, amount_format, status, version
			FROM users WHERE email_normalized = ?
			""", email);
		assertNotNull(user.get("email_verified_at"));
		assertTrue(((String) user.get("password_hash")).startsWith("$argon2id$"));
		assertFalse(((String) user.get("password_hash")).contains(PASSWORD));
		assertEquals(1, ((Number) user.get("password_hash_version")).intValue());
		assertEquals("昵称", user.get("nickname"));
		assertEquals("Asia/Shanghai", user.get("timezone"));
		assertEquals("CNY", user.get("base_currency").toString().trim());
		assertEquals("zh-CN", user.get("locale"));
		assertEquals("STANDARD", user.get("amount_format"));
		assertEquals("ACTIVE", user.get("status"));
		assertEquals(1, ((Number) user.get("version")).intValue());
		assertTrue(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM email_challenges "
			+ "WHERE email_normalized = ?", Boolean.class, email));
	}

	@Test
	void wrongExpiredRepeatedAndResetChallengesNeverCreateAdditionalUsers() {
		String wrong = "registration-wrong@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, wrong, "123456", NOW);
		assertThrows(RegistrationValidationException.class,
			() -> registrationService(NOW, userRegistrationPort).register(command(wrong, "999999")));
		assertEquals(0, userCount(wrong));

		String expired = "registration-expired@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, expired, "123456", NOW.minusSeconds(600));
		assertThrows(RegistrationValidationException.class,
			() -> registrationService(NOW, userRegistrationPort).register(command(expired, "123456")));
		assertEquals(0, userCount(expired));
		assertEquals("EXPIRED", jdbc.queryForObject("SELECT invalidation_reason FROM email_challenges "
			+ "WHERE email_normalized = ?", String.class, expired));

		String repeated = "registration-repeated@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, repeated, "123456", NOW);
		registrationService(NOW, userRegistrationPort).register(command(repeated, "123456"));
		assertThrows(RegistrationValidationException.class,
			() -> registrationService(NOW, userRegistrationPort).register(command(repeated, "123456")));
		assertEquals(1, userCount(repeated));

		String reset = "registration-reset@example.test";
		insertChallenge(EmailChallengePurpose.RESET_PASSWORD, reset, "123456", NOW);
		assertThrows(RegistrationValidationException.class,
			() -> registrationService(NOW, userRegistrationPort).register(command(reset, "123456")));
		assertEquals(0, userCount(reset));
	}

	@Test
	void concurrentRegisterAttemptsConsumeOneChallengeAndCreateAtMostOneUser() throws Exception {
		String email = "registration-concurrent@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, email, "123456", NOW);
		EmailRegistrationApplicationService service = registrationService(NOW, userRegistrationPort);

		List<Boolean> results = runConcurrently(2, () -> {
			try {
				service.register(command(email, "123456"));
				return true;
			} catch (RegistrationValidationException exception) {
				return false;
			}
		});

		assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
		assertEquals(1, userCount(email));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, email));
	}

	@Test
	void caseAndNfkcEquivalentEmailsCannotRegisterTwiceAndConflictRollsBackConsumption() {
		String canonical = "registration-nfkc@example.test";
		String equivalent = "ＲＥＧＩＳＴＲＡＴＩＯＮ－ＮＦＫＣ@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, canonical, "123456", NOW);
		registrationService(NOW, userRegistrationPort).register(command(canonical, "123456"));
		// 新挑战必须晚于已消费挑战，才能稳定验证唯一冲突会回滚其消费。
		insertChallenge(EmailChallengePurpose.REGISTER, equivalent, "123456", NOW.plusSeconds(1));

		assertThrows(EmailAlreadyRegisteredException.class,
			() -> registrationService(NOW.plusSeconds(1), userRegistrationPort).register(command(equivalent, "123456")));
		assertEquals(1, userCount(canonical));
		assertFalse(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NULL", Boolean.class, canonical));
	}

	@Test
	void userWriteFailureRollsBackChallengeConsumption() {
		String email = "registration-rollback@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, email, "123456", NOW);
		UserRegistrationPort failingPort = command -> {
			throw new UserPersistenceException(new IllegalStateException("测试 users 写入失败。"));
		};

		assertThrows(UserPersistenceException.class,
			() -> registrationService(NOW, failingPort).register(command(email, "123456")));
		assertEquals(0, userCount(email));
		assertFalse(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM email_challenges "
			+ "WHERE email_normalized = ?", Boolean.class, email));
	}

	@Test
	void passwordHashFailureRollsBackChallengeConsumption() {
		String email = "registration-hash-rollback@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, email, "123456", NOW);
		PasswordHasher failingHasher = new PasswordHasher() {
			@Override
			public String hash(String password) {
				throw new PasswordHashingException(new IllegalStateException("测试密码 Hash 失败。"));
			}

			@Override
			public boolean supports(int hashVersion, String encodedHash) {
				return false;
			}

			@Override
			public boolean matches(String password, String encodedHash) {
				return false;
			}
		};

		assertThrows(PasswordHashingException.class,
			() -> registrationService(NOW, userRegistrationPort, failingHasher).register(command(email, "123456")));
		assertEquals(0, userCount(email));
		assertFalse(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM email_challenges "
			+ "WHERE email_normalized = ?", Boolean.class, email));
	}

	@Test
	void samePasswordForDifferentUsersProducesDistinctArgon2idHashesWithoutPlaintext() {
		String first = "registration-hash-first@example.test";
		String second = "registration-hash-second@example.test";
		insertChallenge(EmailChallengePurpose.REGISTER, first, "123456", NOW);
		insertChallenge(EmailChallengePurpose.REGISTER, second, "123456", NOW);

		registrationService(NOW, userRegistrationPort).register(command(first, "123456"));
		registrationService(NOW, userRegistrationPort).register(command(second, "123456"));

		String firstHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email_normalized = ?",
			String.class, first);
		String secondHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email_normalized = ?",
			String.class, second);
		assertTrue(firstHash.startsWith("$argon2id$"));
		assertTrue(secondHash.startsWith("$argon2id$"));
		assertNotEquals(firstHash, secondHash);
		assertFalse(firstHash.contains(PASSWORD));
		assertFalse(secondHash.contains(PASSWORD));
	}

	private EmailRegistrationApplicationService registrationService(Instant now, UserRegistrationPort port) {
		return registrationService(now, port, passwordHasher);
	}

	private EmailRegistrationApplicationService registrationService(
		Instant now,
		UserRegistrationPort port,
		PasswordHasher hasher) {
		EmailChallengeApplicationService challengeService = new EmailChallengeApplicationService(
			transactionRunner, challengeStore, rateLimitStore, codeGenerator, codeHasher,
			envelopeEncryptor, outbox, Clock.fixed(now, ZoneOffset.UTC));
		return new EmailRegistrationApplicationService(
			transactionRunner, challengeService, hasher, port,
			Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
	}

	private void insertChallenge(EmailChallengePurpose purpose, String email, String code, Instant createdAt) {
		String normalizedEmail = EmailAddress.normalize(email).value();
		transactionRunner.required(() -> challengeStore.insert(EmailChallenge.issue(
			UUID.randomUUID(), purpose, normalizedEmail, codeHasher.hash(purpose, normalizedEmail, code), createdAt)));
	}

	private int userCount(String email) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email_normalized = ?", Integer.class,
			EmailAddress.normalize(email).value());
	}

	private static EmailRegistrationCommand command(String email, String code) {
		return new EmailRegistrationCommand(email, code, PASSWORD, "昵称", "Asia/Shanghai", "CNY", "zh-CN");
	}

	private static <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				futures.add(executor.submit(task));
			}
			List<T> results = new ArrayList<>();
			for (java.util.concurrent.Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}
}
