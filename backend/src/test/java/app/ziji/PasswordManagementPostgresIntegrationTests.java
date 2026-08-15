package app.ziji;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.AuthenticatedPasswordChangeCommand;
import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeOutbox;
import app.ziji.auth.application.EnvelopeEncryptor;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordHashingException;
import app.ziji.auth.application.PasswordManagementApplicationService;
import app.ziji.auth.application.PasswordManagementValidationException;
import app.ziji.auth.application.PasswordResetCommand;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.auth.application.VerificationCodeGenerator;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.auth.infrastructure.AuthInfrastructureException;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPasswordManagementPort;
import app.ziji.user.application.UserPersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 密码重置与改密真实 PostgreSQL 验收：挑战、users 行锁、会话撤销和整体回滚。 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordManagementPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final String OLD_PASSWORD = "old-password";
	private static final String NEW_PASSWORD = "new-password";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

	@Autowired
	private AuthRateLimitStore rateLimitStore;

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
	private UserPasswordManagementPort userPasswordPort;

	@Autowired
	private DeviceSessionStore sessionStore;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private SecureRandom secureRandom;

	@BeforeEach
	void clearPasswordManagementFacts() {
		jdbc.execute("TRUNCATE TABLE session_refresh_tokens, user_sessions");
		jdbc.update("DELETE FROM email_challenges WHERE email_normalized LIKE 'password-management-%@example.test'");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'password-management-%@example.test'");
	}

	@AfterEach
	void removeFailureTriggers() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_password_update_for_test ON users");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_password_update_for_test()");
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_password_session_revocation_for_test ON user_sessions");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_password_session_revocation_for_test()");
		jdbc.execute("ALTER TABLE session_refresh_tokens ENABLE TRIGGER trg_validate_refresh_token_session_window");
		jdbc.execute("ALTER TABLE user_sessions ENABLE TRIGGER trg_validate_user_session_security_baseline");
	}

	@Test
	void resetUpdatesOnlyPasswordFactsAndRevokesEveryCurrentSessionToken() {
		String email = "password-management-reset@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		SessionTokenResult first = sessionServiceAt(NOW).createForAuthenticatedUser(
			new app.ziji.auth.application.CreateDeviceSessionCommand(userId, "Phone", "password-reset-phone"));
		SessionTokenResult second = sessionServiceAt(NOW).createForAuthenticatedUser(
			new app.ziji.auth.application.CreateDeviceSessionCommand(userId, "Tablet", "password-reset-tablet"));
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);
		var before = jdbc.queryForMap("""
			SELECT email, nickname, timezone, base_currency, locale, amount_format, status, version
			FROM users WHERE id = ?
			""", userId);

		passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));

		var after = jdbc.queryForMap("""
			SELECT email, password_hash, password_hash_version, nickname, timezone, base_currency, locale,
				amount_format, status, version, updated_at
			FROM users WHERE id = ?
			""", userId);
		assertEquals(before.get("email"), after.get("email"));
		assertEquals(before.get("nickname"), after.get("nickname"));
		assertEquals(before.get("timezone"), after.get("timezone"));
		assertEquals(before.get("base_currency"), after.get("base_currency"));
		assertEquals(before.get("locale"), after.get("locale"));
		assertEquals(before.get("amount_format"), after.get("amount_format"));
		assertEquals(before.get("status"), after.get("status"));
		assertEquals(2, ((Number) after.get("version")).intValue());
		assertEquals(NOW, ((java.sql.Timestamp) after.get("updated_at")).toInstant());
		String newHash = (String) after.get("password_hash");
		assertTrue(passwordHasher.matches(NEW_PASSWORD, newHash));
		assertFalse(passwordHasher.matches(OLD_PASSWORD, newHash));
		assertEquals(1, ((Number) after.get("password_hash_version")).intValue());

		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, email));
		assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions "
			+ "WHERE user_id = ? AND revoked_at IS NOT NULL AND revoke_reason = 'PASSWORD_RESET'", Integer.class, userId));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens "
			+ "WHERE session_id IN (?, ?) AND consumed_at IS NULL AND revoked_at IS NULL",
			Integer.class, first.sessionId(), second.sessionId()));
		assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens "
			+ "WHERE session_id IN (?, ?) AND revoked_at IS NOT NULL", Integer.class, first.sessionId(), second.sessionId()));
	}

	@Test
	void validChallengeForUnknownEmailSucceedsWithoutCreatingUser() {
		String email = "password-management-unknown@example.test";
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);

		passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));

		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email_normalized = ?", Integer.class, email));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, email));
	}

	@Test
	void resetPreservesLockedAndClosedUserStatus() {
		for (UserCredentialStatus status : List.of(UserCredentialStatus.LOCKED, UserCredentialStatus.CLOSED)) {
			String email = "password-management-reset-" + status.name().toLowerCase() + "@example.test";
			UUID userId = seedUser(email, status);
			insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);

			passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));

			assertEquals(status.name(), jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId));
			assertEquals(2, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, userId));
		}
	}

	@Test
	void invalidExpiredRepeatedAndWrongPurposeChallengesDoNotChangeUser() {
		String email = "password-management-invalid@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);

		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);
		assertThrows(PasswordManagementValidationException.class,
			() -> passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "999999", NEW_PASSWORD)));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, email));
		assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM email_challenges "
			+ "WHERE email_normalized = ?", Integer.class, email));
		assertEquals(oldHash, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId));

		String expired = "password-management-expired@example.test";
		seedUser(expired, UserCredentialStatus.ACTIVE);
		insertChallenge(expired, EmailChallengePurpose.RESET_PASSWORD, NOW.minusSeconds(600));
		assertThrows(PasswordManagementValidationException.class,
			() -> passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(expired, "123456", NEW_PASSWORD)));
		assertEquals("EXPIRED", jdbc.queryForObject("SELECT invalidation_reason FROM email_challenges "
			+ "WHERE email_normalized = ?", String.class, expired));

		String wrongPurpose = "password-management-register@example.test";
		seedUser(wrongPurpose, UserCredentialStatus.ACTIVE);
		insertChallenge(wrongPurpose, EmailChallengePurpose.REGISTER, NOW);
		assertThrows(PasswordManagementValidationException.class,
			() -> passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(wrongPurpose, "123456", NEW_PASSWORD)));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, wrongPurpose));

		passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));
		assertThrows(PasswordManagementValidationException.class,
			() -> passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD)));
	}

	@Test
	void concurrentResetAttemptsConsumeOneChallengeAndCommitOnePasswordUpdate() throws Exception {
		String email = "password-management-concurrent@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);

		List<Boolean> results = runConcurrently(2, () -> {
			try {
				passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));
				return true;
			} catch (PasswordManagementValidationException expected) {
				return false;
			}
		});

		assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, email));
		assertEquals(2, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, userId));
		assertTrue(passwordHasher.matches(NEW_PASSWORD,
			jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId)));
	}

	@Test
	void hashFailureAndUserFailureRollBackChallengeAndPassword() {
		String hashFailureEmail = "password-management-hash-failure@example.test";
		UUID hashFailureUser = seedUser(hashFailureEmail, UserCredentialStatus.ACTIVE);
		String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, hashFailureUser);
		insertChallenge(hashFailureEmail, EmailChallengePurpose.RESET_PASSWORD, NOW);
		PasswordHasher failingHasher = new PasswordHasher() {
			@Override
			public String hash(String password) {
				throw new PasswordHashingException(new IllegalStateException("test hash failure"));
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
			() -> passwordServiceAt(NOW, failingHasher).resetPassword(
				new PasswordResetCommand(hashFailureEmail, "123456", NEW_PASSWORD)));
		assertEquals(oldHash, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, hashFailureUser));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, hashFailureEmail));

		String userFailureEmail = "password-management-user-failure@example.test";
		UUID userFailureUser = seedUser(userFailureEmail, UserCredentialStatus.ACTIVE);
		String userFailureOldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userFailureUser);
		insertChallenge(userFailureEmail, EmailChallengePurpose.RESET_PASSWORD, NOW);
		createPasswordUpdateFailureTrigger();
		assertThrows(UserPersistenceException.class,
			() -> passwordServiceAt(NOW).resetPassword(
				new PasswordResetCommand(userFailureEmail, "123456", NEW_PASSWORD)));
		assertEquals(userFailureOldHash, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userFailureUser));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, userFailureEmail));
	}

	@Test
	void sessionRevocationFailureRollsBackChallengePasswordAndSession() {
		String email = "password-management-session-failure@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		SessionTokenResult session = sessionServiceAt(NOW).createForAuthenticatedUser(
			new app.ziji.auth.application.CreateDeviceSessionCommand(userId, "Phone", "password-session-failure"));
		String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);
		createSessionRevocationFailureTrigger();

		assertThrows(AuthInfrastructureException.class,
			() -> passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD)));

		assertEquals(oldHash, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NULL", Integer.class, email));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions "
			+ "WHERE id = ? AND revoked_at IS NOT NULL", Integer.class, session.sessionId()));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens "
			+ "WHERE session_id = ? AND revoked_at IS NOT NULL", Integer.class, session.sessionId()));
	}

	@Test
	void passwordChangeUpdatesOnlyCredentialFactsAndKeepsSessionsActive() {
		String email = "password-management-change@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		SessionTokenResult session = sessionServiceAt(NOW).createForAuthenticatedUser(
			new app.ziji.auth.application.CreateDeviceSessionCommand(userId, "Browser", "password-change-browser"));
		var before = jdbc.queryForMap("SELECT email, nickname, timezone, base_currency, locale, amount_format, status, version "
			+ "FROM users WHERE id = ?", userId);

		passwordServiceAt(NOW).changePassword(new AuthenticatedPasswordChangeCommand(userId, OLD_PASSWORD, NEW_PASSWORD));

		var after = jdbc.queryForMap("SELECT email, password_hash, password_hash_version, nickname, timezone, base_currency, locale, "
			+ "amount_format, status, version, updated_at FROM users WHERE id = ?", userId);
		assertEquals(before.get("email"), after.get("email"));
		assertEquals(before.get("nickname"), after.get("nickname"));
		assertEquals(before.get("timezone"), after.get("timezone"));
		assertEquals(before.get("base_currency"), after.get("base_currency"));
		assertEquals(before.get("locale"), after.get("locale"));
		assertEquals(before.get("amount_format"), after.get("amount_format"));
		assertEquals(before.get("status"), after.get("status"));
		assertEquals(2, ((Number) after.get("version")).intValue());
		assertEquals(NOW, ((java.sql.Timestamp) after.get("updated_at")).toInstant());
		assertTrue(passwordHasher.matches(NEW_PASSWORD, (String) after.get("password_hash")));
		assertFalse(passwordHasher.matches(OLD_PASSWORD, (String) after.get("password_hash")));
		assertEquals(1, ((Number) after.get("password_hash_version")).intValue());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL",
			Integer.class, session.sessionId()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens "
			+ "WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL", Integer.class, session.sessionId()));
	}

	@Test
	void closingUserCanChangePasswordWithoutChangingStatus() {
		String email = "password-management-change-closing@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.CLOSING);

		passwordServiceAt(NOW).changePassword(new AuthenticatedPasswordChangeCommand(
			userId, OLD_PASSWORD, NEW_PASSWORD));

		assertEquals(UserCredentialStatus.CLOSING.name(),
			jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId));
		assertTrue(passwordHasher.matches(NEW_PASSWORD,
			jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId)));
		assertEquals(2, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, userId));
	}

	@Test
	void passwordChangeRejectsLockedClosedAndWrongPasswordWithoutMutation() {
		for (UserCredentialStatus status : List.of(UserCredentialStatus.LOCKED, UserCredentialStatus.CLOSED)) {
			String email = "password-management-status-" + status.name().toLowerCase() + "@example.test";
			UUID userId = seedUser(email, status);
			String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
			assertThrows(app.ziji.auth.application.InvalidCredentialsException.class,
				() -> passwordServiceAt(NOW).changePassword(
					new AuthenticatedPasswordChangeCommand(userId, OLD_PASSWORD, NEW_PASSWORD)));
			assertEquals(oldHash, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId));
			assertEquals(1, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, userId));
		}
		UUID absent = UUID.randomUUID();
		assertThrows(app.ziji.auth.application.InvalidCredentialsException.class,
			() -> passwordServiceAt(NOW).changePassword(
				new AuthenticatedPasswordChangeCommand(absent, OLD_PASSWORD, NEW_PASSWORD)));

		String wrongPasswordEmail = "password-management-wrong-password@example.test";
		UUID wrongPasswordUser = seedUser(wrongPasswordEmail, UserCredentialStatus.ACTIVE);
		String oldHash = jdbc.queryForObject(
			"SELECT password_hash FROM users WHERE id = ?", String.class, wrongPasswordUser);
		assertThrows(app.ziji.auth.application.InvalidCredentialsException.class,
			() -> passwordServiceAt(NOW).changePassword(
				new AuthenticatedPasswordChangeCommand(wrongPasswordUser, "wrong-password", NEW_PASSWORD)));
		assertEquals(oldHash,
			jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, wrongPasswordUser));
		assertEquals(1, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, wrongPasswordUser));
	}

	@Test
	void passwordChangeRejectsUnsupportedAndMalformedStoredHashes() {
		String unsupportedEmail = "password-management-unsupported-hash@example.test";
		UUID unsupportedUser = seedUser(unsupportedEmail, UserCredentialStatus.ACTIVE);
		jdbc.update("UPDATE users SET password_hash_version = 2 WHERE id = ?", unsupportedUser);
		assertThrows(app.ziji.auth.application.InvalidCredentialsException.class,
			() -> passwordServiceAt(NOW).changePassword(
				new AuthenticatedPasswordChangeCommand(unsupportedUser, OLD_PASSWORD, NEW_PASSWORD)));
		assertEquals(2, jdbc.queryForObject(
			"SELECT password_hash_version FROM users WHERE id = ?", Integer.class, unsupportedUser));

		String malformedEmail = "password-management-malformed-hash@example.test";
		UUID malformedUser = seedUser(malformedEmail, UserCredentialStatus.ACTIVE);
		jdbc.update("UPDATE users SET password_hash = '$argon2id$broken' WHERE id = ?", malformedUser);
		assertThrows(app.ziji.auth.application.InvalidCredentialsException.class,
			() -> passwordServiceAt(NOW).changePassword(
				new AuthenticatedPasswordChangeCommand(malformedUser, OLD_PASSWORD, NEW_PASSWORD)));
		assertEquals("$argon2id$broken",
			jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, malformedUser));
		assertEquals(1, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, malformedUser));
	}

	@Test
	void concurrentPasswordChangesWithOneOldPasswordAllowOnlyOneSuccess() throws Exception {
		String email = "password-management-change-concurrent@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		List<Boolean> results = runConcurrently(2, () -> {
			try {
				passwordServiceAt(NOW).changePassword(new AuthenticatedPasswordChangeCommand(userId, OLD_PASSWORD, NEW_PASSWORD));
				return true;
			} catch (app.ziji.auth.application.InvalidCredentialsException expected) {
				return false;
			}
		});

		assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
		assertEquals(2, jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, userId));
		assertTrue(passwordHasher.matches(NEW_PASSWORD,
			jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId)));
	}

	@Test
	void legacyNullBaselineResetUsesSecurityAdminAndPreservesHistoricalFacts() {
		String email = "password-management-legacy@example.test";
		UUID userId = seedUser(email, UserCredentialStatus.ACTIVE);
		UUID sessionId = UUID.randomUUID();
		UUID tokenId = UUID.randomUUID();
		Instant issuedAt = NOW.minusSeconds(3_600);
		Instant expiresAt = NOW.plusSeconds(7 * 24 * 60 * 60L);
		jdbc.execute("ALTER TABLE session_refresh_tokens DISABLE TRIGGER trg_validate_refresh_token_session_window");
		jdbc.execute("ALTER TABLE user_sessions DISABLE TRIGGER trg_validate_user_session_security_baseline");
		try {
			jdbc.update("""
				INSERT INTO user_sessions
					(id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at,
					 security_baseline_version)
				VALUES (?, ?, ' legacy-password-device ', NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL,
					CAST(? AS timestamptz), NULL)
				""", sessionId, userId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString());
			jdbc.update("""
				INSERT INTO session_refresh_tokens
					(id, session_id, token_hash, issued_at, expires_at, consumed_at, revoked_at, replaced_by_id, created_at,
					 security_baseline_version)
				VALUES (?, ?, 'legacy-password-token', CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL, NULL,
					CAST(? AS timestamptz), NULL)
				""", tokenId, sessionId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString());
		} finally {
			jdbc.execute("ALTER TABLE user_sessions ENABLE TRIGGER trg_validate_user_session_security_baseline");
			jdbc.execute("ALTER TABLE session_refresh_tokens ENABLE TRIGGER trg_validate_refresh_token_session_window");
		}
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, NOW);

		passwordServiceAt(NOW).resetPassword(new PasswordResetCommand(email, "123456", NEW_PASSWORD));

		assertEquals("SECURITY_ADMIN", jdbc.queryForObject("SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, sessionId));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND security_baseline_version IS NULL "
			+ "AND device_id = ' legacy-password-device ' AND device_name IS NULL AND issued_at = CAST(? AS timestamptz) "
			+ "AND expires_at = CAST(? AS timestamptz) AND last_seen_at = CAST(? AS timestamptz)",
			Integer.class, sessionId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens WHERE id = ? "
			+ "AND token_hash = 'legacy-password-token' AND security_baseline_version IS NULL AND revoked_at IS NOT NULL",
			Integer.class, tokenId));
	}

	private PasswordManagementApplicationService passwordServiceAt(Instant now) {
		return passwordServiceAt(now, passwordHasher);
	}

	private PasswordManagementApplicationService passwordServiceAt(Instant now, PasswordHasher hasher) {
		EmailChallengeApplicationService challengeService = new EmailChallengeApplicationService(
			transactionRunner, challengeStore, rateLimitStore, codeGenerator, codeHasher,
			envelopeEncryptor, outbox, Clock.fixed(now, ZoneOffset.UTC));
		DeviceSessionApplicationService deviceService = sessionServiceAt(now);
		return new PasswordManagementApplicationService(
			transactionRunner, challengeService, hasher, userPasswordPort, deviceService,
			Clock.fixed(now, ZoneOffset.UTC));
	}

	private DeviceSessionApplicationService sessionServiceAt(Instant now) {
		return new DeviceSessionApplicationService(transactionRunner, sessionStore, accessTokenService, secureRandom,
			Clock.fixed(now, ZoneOffset.UTC));
	}

	private UUID seedUser(String email, UserCredentialStatus status) {
		UUID userId = UUID.randomUUID();
		String hash = passwordHasher.hash(OLD_PASSWORD);
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '密码测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, EmailAddress.normalize(email).value(), NOW.toString(), hash, status.name(), NOW.toString(), NOW.toString());
		return userId;
	}

	private void insertChallenge(String email, EmailChallengePurpose purpose, Instant createdAt) {
		String normalized = EmailAddress.normalize(email).value();
		transactionRunner.required(() -> challengeStore.insert(EmailChallenge.issue(
			UUID.randomUUID(), purpose, normalized, codeHasher.hash(purpose, normalized, "123456"), createdAt)));
	}

	private void createPasswordUpdateFailureTrigger() {
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_password_update_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
					RAISE EXCEPTION 'test-only password update failure';
				END IF;
				RETURN NEW;
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_password_update_for_test
			BEFORE UPDATE ON users
			FOR EACH ROW EXECUTE FUNCTION reject_password_update_for_test()
			""");
	}

	private void createSessionRevocationFailureTrigger() {
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_password_session_revocation_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				IF NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
					RAISE EXCEPTION 'test-only password session revocation failure';
				END IF;
				RETURN NEW;
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_password_session_revocation_for_test
			BEFORE UPDATE ON user_sessions
			FOR EACH ROW EXECUTE FUNCTION reject_password_session_revocation_for_test()
			""");
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
