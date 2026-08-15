package app.ziji;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeOutbox;
import app.ziji.auth.application.InvalidCredentialsException;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordLoginApplicationService;
import app.ziji.auth.application.PasswordLoginCommand;
import app.ziji.auth.application.PasswordManagementApplicationService;
import app.ziji.auth.application.PasswordResetCommand;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.auth.application.SessionTokenValidationException;
import app.ziji.auth.application.VerificationCodeGenerator;
import app.ziji.auth.application.EnvelopeEncryptor;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialLookupPort;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPasswordManagementPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 变更基线的第一条证据：确定性卡住旧 Hash 读取与会话创建之间的窗口。 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetLoginRacePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final SourceAddress SOURCE = SourceAddress.parseLiteral("192.0.2.60");
	private static final String OLD_PASSWORD = "old-password";
	private static final String NEW_PASSWORD = "new-password";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private AuthRateLimitStore rateLimitStore;

	@Autowired
	private UserCredentialLookupPort credentialLookupPort;

	@Autowired
	private UserPasswordManagementPort userPasswordPort;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

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
	private DeviceSessionStore sessionStore;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private SecureRandom secureRandom;

	@BeforeEach
	void clearRaceFacts() {
		jdbc.execute("TRUNCATE TABLE session_refresh_tokens, user_sessions, auth_rate_limit_buckets");
		jdbc.update("DELETE FROM email_challenges WHERE email_normalized LIKE 'password-race-%@example.test'");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'password-race-%@example.test'");
	}

	@Test
	void loginTransactionCreatesSessionBeforeResetThenResetRevokesIt() throws Exception {
		String email = "password-race-current@example.test";
		UUID userId = seedUser(email);
		DeviceSessionApplicationService sessionService = sessionServiceAt(NOW);
		var existing = sessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Existing", "existing-device"));
		insertResetChallenge(email);

		BlockingCredentialLookupPort blockingLookup = new BlockingCredentialLookupPort(credentialLookupPort);
		PasswordLoginApplicationService loginService = new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, blockingLookup, passwordHasher, sessionService);
		BlockingPasswordManagementPort blockingPasswordPort = new BlockingPasswordManagementPort(userPasswordPort);
		PasswordManagementApplicationService resetService = passwordServiceAt(NOW, blockingPasswordPort);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<app.ziji.auth.application.SessionTokenResult> login = executor.submit(() -> loginService.loginAndCreateSession(
				new PasswordLoginCommand(email, OLD_PASSWORD, SOURCE, NOW), "Raced", "raced-device"));

			assertTrue(blockingLookup.credentialsRead.await(10, TimeUnit.SECONDS));
			Future<?> reset = executor.submit(() -> resetService.resetPassword(
				new PasswordResetCommand(email, "123456", NEW_PASSWORD)));
			assertTrue(blockingPasswordPort.updateStarted.await(10, TimeUnit.SECONDS));
			blockingLookup.release.countDown();
			var createdBeforeReset = login.get(10, TimeUnit.SECONDS);
			reset.get(10, TimeUnit.SECONDS);

			assertEquals(userId, jdbc.queryForObject("SELECT user_id FROM user_sessions WHERE id = ?", UUID.class,
				createdBeforeReset.sessionId()));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions "
				+ "WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId));
			assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ?", Integer.class, userId));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens t "
				+ "JOIN user_sessions s ON s.id = t.session_id "
				+ "WHERE s.user_id = ? AND t.consumed_at IS NULL AND t.revoked_at IS NULL", Integer.class, userId));
			assertEquals("PASSWORD_RESET", jdbc.queryForObject(
				"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, existing.sessionId()));
			assertEquals("PASSWORD_RESET", jdbc.queryForObject(
				"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, createdBeforeReset.sessionId()));
		} finally {
			blockingLookup.release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void resetCommitsBeforeLoginReadsUsersAndOldPasswordCreatesNoSession() {
		String email = "password-race-reset-first@example.test";
		UUID userId = seedUser(email);
		insertResetChallenge(email);
		PasswordManagementApplicationService resetService = passwordServiceAt(NOW);
		resetService.resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));

		DeviceSessionApplicationService sessionService = sessionServiceAt(NOW);
		PasswordLoginApplicationService loginService = new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher, sessionService);
		assertThrows(InvalidCredentialsException.class, () -> loginService.loginAndCreateSession(
			new PasswordLoginCommand(email, OLD_PASSWORD, SOURCE, NOW), "Old", "old-device"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ?", Integer.class, userId));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens", Integer.class));

		SessionTokenResult newLogin = loginService.loginAndCreateSession(
			new PasswordLoginCommand(email, NEW_PASSWORD, SOURCE, NOW), "New", "new-device");
		assertEquals(userId, jdbc.queryForObject("SELECT user_id FROM user_sessions WHERE id = ?", UUID.class,
			newLogin.sessionId()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ?",
			Integer.class, newLogin.sessionId()));
	}

	@Test
	void concurrentOldPasswordLoginsAndOneResetLeaveNoActiveSessionOrToken() throws Exception {
		String email = "password-race-many@example.test";
		UUID userId = seedUser(email);
		insertResetChallenge(email);
		DeviceSessionApplicationService sessionService = sessionServiceAt(NOW);
		BlockingCredentialLookupPort blockingLookup = new BlockingCredentialLookupPort(credentialLookupPort);
		PasswordLoginApplicationService firstLogin = new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, blockingLookup, passwordHasher, sessionService);
		PasswordLoginApplicationService normalLogin = new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher, sessionService);
		BlockingPasswordManagementPort blockingPasswordPort = new BlockingPasswordManagementPort(userPasswordPort);
		PasswordManagementApplicationService resetService = passwordServiceAt(NOW, blockingPasswordPort);
		ExecutorService executor = Executors.newFixedThreadPool(6);
		try {
			Future<SessionTokenResult> first = executor.submit(() -> firstLogin.loginAndCreateSession(
				new PasswordLoginCommand(email, OLD_PASSWORD, SOURCE, NOW), "First", "first-device"));
			assertTrue(blockingLookup.credentialsRead.await(10, TimeUnit.SECONDS));
			List<Future<Boolean>> others = new java.util.ArrayList<>();
			for (int index = 0; index < 3; index++) {
				String device = "parallel-device-" + index;
				others.add(executor.submit(() -> {
					try {
						normalLogin.loginAndCreateSession(
							new PasswordLoginCommand(email, OLD_PASSWORD, SOURCE, NOW), "Parallel", device);
						return true;
					} catch (InvalidCredentialsException expected) {
						return false;
					}
				}));
			}
			Future<?> reset = executor.submit(() -> resetService.resetPassword(
				new PasswordResetCommand(email, "123456", NEW_PASSWORD)));
			assertTrue(blockingPasswordPort.updateStarted.await(10, TimeUnit.SECONDS));
			blockingLookup.release.countDown();
			first.get(10, TimeUnit.SECONDS);
			reset.get(10, TimeUnit.SECONDS);
			for (Future<Boolean> other : others) {
				other.get(10, TimeUnit.SECONDS);
			}

			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions "
				+ "WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens t "
				+ "JOIN user_sessions s ON s.id = t.session_id "
				+ "WHERE s.user_id = ? AND t.consumed_at IS NULL AND t.revoked_at IS NULL", Integer.class, userId));
			assertTrue(jdbc.queryForList("SELECT revoke_reason FROM user_sessions WHERE user_id = ?", String.class, userId)
				.stream().allMatch("PASSWORD_RESET"::equals));
		} finally {
			blockingLookup.release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void sessionCreationFailureKeepsCommittedLoginRateLimitAndLeavesNoFacts() {
		String email = "password-race-session-failure@example.test";
		UUID userId = seedUser(email);
		DeviceSessionApplicationService sessionService = sessionServiceAt(NOW);
		PasswordLoginApplicationService loginService = new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher, sessionService);

		assertThrows(SessionTokenValidationException.class, () -> loginService.loginAndCreateSession(
			new PasswordLoginCommand(email, OLD_PASSWORD, SOURCE, NOW), " ", "failure-device"));
		assertEquals(1, jdbc.queryForObject("SELECT MAX(request_count) FROM auth_rate_limit_buckets "
			+ "WHERE purpose = 'LOGIN' AND dimension = 'EMAIL'", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ?", Integer.class, userId));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens", Integer.class));
	}

	private PasswordManagementApplicationService passwordServiceAt(Instant now) {
		return passwordServiceAt(now, userPasswordPort);
	}

	private PasswordManagementApplicationService passwordServiceAt(Instant now, UserPasswordManagementPort passwordPort) {
		EmailChallengeApplicationService challengeService = new EmailChallengeApplicationService(
			transactionRunner, challengeStore, rateLimitStore, codeGenerator, codeHasher,
			envelopeEncryptor, outbox, Clock.fixed(now, ZoneOffset.UTC));
		return new PasswordManagementApplicationService(
			transactionRunner, challengeService, passwordHasher, passwordPort,
			sessionServiceAt(now), Clock.fixed(now, ZoneOffset.UTC));
	}

	private DeviceSessionApplicationService sessionServiceAt(Instant now) {
		return new DeviceSessionApplicationService(transactionRunner, sessionStore, accessTokenService, secureRandom,
			Clock.fixed(now, ZoneOffset.UTC));
	}

	private UUID seedUser(String email) {
		UUID userId = UUID.randomUUID();
		String hash = passwordHasher.hash(OLD_PASSWORD);
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '竞态测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, EmailAddress.normalize(email).value(), NOW.toString(), hash,
			NOW.toString(), NOW.toString());
		return userId;
	}

	private void insertResetChallenge(String email) {
		String normalizedEmail = EmailAddress.normalize(email).value();
		transactionRunner.required(() -> challengeStore.insert(EmailChallenge.issue(
			UUID.randomUUID(), EmailChallengePurpose.RESET_PASSWORD, normalizedEmail,
			codeHasher.hash(EmailChallengePurpose.RESET_PASSWORD, normalizedEmail, "123456"), NOW)));
	}

	private static final class BlockingCredentialLookupPort implements UserCredentialLookupPort {
		private final UserCredentialLookupPort delegate;
		private final CountDownLatch credentialsRead = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		private BlockingCredentialLookupPort(UserCredentialLookupPort delegate) {
			this.delegate = delegate;
		}

		@Override
		public Optional<UserCredential> findByNormalizedEmail(String emailNormalized) {
			return findAndBlock(emailNormalized, false);
		}

		@Override
		public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
			return findAndBlock(emailNormalized, true);
		}

		private Optional<UserCredential> findAndBlock(String emailNormalized, boolean forUpdate) {
			Optional<UserCredential> result = forUpdate
				? delegate.findByNormalizedEmailForUpdate(emailNormalized)
				: delegate.findByNormalizedEmail(emailNormalized);
			credentialsRead.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError("测试闩锁未释放。");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("测试线程被中断。", exception);
			}
			return result;
		}
	}

	private static final class BlockingPasswordManagementPort implements UserPasswordManagementPort {
		private final UserPasswordManagementPort delegate;
		private final CountDownLatch updateStarted = new CountDownLatch(1);

		private BlockingPasswordManagementPort(UserPasswordManagementPort delegate) {
			this.delegate = delegate;
		}

		@Override
		public Optional<UserCredential> findByUserIdForUpdate(UUID userId) {
			return delegate.findByUserIdForUpdate(userId);
		}

		@Override
		public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
			updateStarted.countDown();
			return delegate.findByNormalizedEmailForUpdate(emailNormalized);
		}

		@Override
		public Optional<UUID> updatePasswordByNormalizedEmail(String emailNormalized, String passwordHash, Instant updatedAt) {
			return delegate.updatePasswordByNormalizedEmail(emailNormalized, passwordHash, updatedAt);
		}

		@Override
		public void updatePasswordForUser(UUID userId, String passwordHash, Instant updatedAt) {
			delegate.updatePasswordForUser(userId, passwordHash, updatedAt);
		}

	}
}
