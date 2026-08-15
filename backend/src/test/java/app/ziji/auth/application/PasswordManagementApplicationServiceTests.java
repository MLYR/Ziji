package app.ziji.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.DeviceId;
import app.ziji.auth.domain.DeviceName;
import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.EmailChallengeStatus;
import app.ziji.auth.domain.SessionRevocationReason;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.auth.domain.StoredRefreshToken;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPasswordManagementPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 密码管理 application 编排测试：格式先行、调用次数、统一失败和事务内安全处置。 */
class PasswordManagementApplicationServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final String EMAIL = "user@example.com";
	private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	@Test
	void invalidResetAndChangeInputsFailBeforeTransactionOrSecurityCalls() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);

		assertThrows(PasswordManagementValidationException.class, () -> fixture.service.resetPassword(null));
		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.resetPassword(new PasswordResetCommand("not-an-email", "123456", "new-password")));
		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.resetPassword(new PasswordResetCommand(EMAIL, "12345", "new-password")));
		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.resetPassword(new PasswordResetCommand(EMAIL, "123456", "short")));
		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
				USER_ID, "", "new-password")));
		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
				USER_ID, "current-password", "short")));

		assertEquals(0, fixture.transactions.calls);
		assertEquals(0, fixture.hasher.hashCalls);
		assertEquals(0, fixture.hasher.supportsCalls);
		assertEquals(0, fixture.hasher.matchesCalls);
		assertEquals(0, fixture.userPort.calls);
		assertEquals(0, fixture.sessionStore.revokeCalls);
	}

	@Test
	void invalidChallengeCommitsAttemptButNeverHashesOrWritesPassword() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);

		assertThrows(PasswordManagementValidationException.class,
			() -> fixture.service.resetPassword(new PasswordResetCommand(EMAIL, "999999", "new-password")));

		assertEquals(1, fixture.challengeStore.failedAttempts);
		assertEquals(0, fixture.hasher.hashCalls);
		assertEquals(0, fixture.userPort.calls);
		assertEquals(0, fixture.sessionStore.revokeCalls);
		assertEquals(EmailChallengeStatus.ACTIVE, fixture.challengeStore.challenge.status());
	}

	@Test
	void expiredConsumedWrongPurposeMissingAndMaxAttemptChallengesNeverHash() {
		Fixture expired = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW.minusSeconds(600));
		assertThrows(PasswordManagementValidationException.class,
			() -> expired.service.resetPassword(command("123456")));
		assertEquals(0, expired.hasher.hashCalls);

		Fixture consumed = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		consumed.challengeStore.challenge = consumed.challengeStore.challenge.consumeAt(NOW);
		assertThrows(PasswordManagementValidationException.class,
			() -> consumed.service.resetPassword(command("123456")));
		assertEquals(0, consumed.hasher.hashCalls);

		Fixture wrongPurpose = fixture(EmailChallengePurpose.REGISTER, NOW);
		assertThrows(PasswordManagementValidationException.class,
			() -> wrongPurpose.service.resetPassword(command("123456")));
		assertEquals(0, wrongPurpose.hasher.hashCalls);

		Fixture missing = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		missing.challengeStore.challenge = null;
		assertThrows(PasswordManagementValidationException.class,
			() -> missing.service.resetPassword(command("123456")));
		assertEquals(0, missing.hasher.hashCalls);

		Fixture maxAttempts = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThrows(PasswordManagementValidationException.class,
				() -> maxAttempts.service.resetPassword(command("999999")));
		}
		assertEquals(EmailChallengeStatus.MAX_ATTEMPTS, maxAttempts.challengeStore.challenge.status());
		assertEquals(0, maxAttempts.hasher.hashCalls);
	}

	@Test
	void validResetNormalizesEmailHashesOnceUpdatesUserAndRevokesAllSessions() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		fixture.userPort.byEmail.put(EMAIL, credential(USER_ID, "old-password", UserCredentialStatus.ACTIVE, fixture.hasher));
		fixture.sessionStore.activeSessions.add(session(USER_ID, "phone", "Phone", NOW));
		fixture.sessionStore.activeSessions.add(session(USER_ID, "tablet", "Tablet", NOW));

		fixture.service.resetPassword(new PasswordResetCommand(" USER@EXAMPLE.COM ", "123456", "new-password"));

		assertEquals(1, fixture.hasher.hashCalls);
		assertEquals("user@example.com", fixture.userPort.lastNormalizedEmail);
		assertEquals(2, fixture.userPort.calls);
		assertEquals("$argon2id$fake-12", fixture.userPort.byEmail.get(EMAIL).passwordHash());
		assertEquals(2, fixture.sessionStore.revokeCalls);
		assertEquals(List.of(SessionRevocationReason.PASSWORD_RESET, SessionRevocationReason.PASSWORD_RESET),
			fixture.sessionStore.revokeReasons);
		assertEquals(EmailChallengeStatus.CONSUMED, fixture.challengeStore.challenge.status());
	}

	@Test
	void validResetForUnknownEmailIsSafeSuccessWithoutUserOrSessionMutation() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);

		fixture.service.resetPassword(command("123456"));

		assertEquals(1, fixture.hasher.hashCalls);
		assertEquals(1, fixture.userPort.calls);
		assertTrue(fixture.userPort.byEmail.isEmpty());
		assertEquals(0, fixture.sessionStore.revokeCalls);
		assertEquals(EmailChallengeStatus.CONSUMED, fixture.challengeStore.challenge.status());
	}

	@Test
	void passwordChangeRequiresSupportedHashAndSingleMatchAndDoesNotRevokeSessions() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		fixture.userPort.byUserId.put(USER_ID, credential(USER_ID, "current-password", UserCredentialStatus.ACTIVE, fixture.hasher));
		fixture.sessionStore.activeSessions.add(session(USER_ID, "phone", "Phone", NOW));
		fixture.hasher.matchesResult = true;

		fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
			USER_ID, "current-password", "new-password"));

		assertEquals(1, fixture.hasher.supportsCalls);
		assertEquals(1, fixture.hasher.matchesCalls);
		assertEquals(1, fixture.hasher.hashCalls);
		assertEquals(2, fixture.userPort.calls);
		assertEquals(0, fixture.sessionStore.revokeCalls);
		assertEquals("$argon2id$fake-12", fixture.userPort.byUserId.get(USER_ID).passwordHash());
	}

	@Test
	void wrongPasswordMatchesOnceAndNeverHashesOrUpdates() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		fixture.userPort.byUserId.put(USER_ID, credential(USER_ID, "current-password", UserCredentialStatus.ACTIVE, fixture.hasher));
		fixture.hasher.matchesResult = false;

		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
				USER_ID, "wrong-password", "new-password")));

		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertEquals(1, fixture.hasher.supportsCalls);
		assertEquals(1, fixture.hasher.matchesCalls);
		assertEquals(0, fixture.hasher.hashCalls);
		assertEquals(0, fixture.userPort.updateCalls);
		assertFalse(failure.getMessage().contains("wrong-password"));
		assertFalse(failure.getMessage().contains(USER_ID.toString()));
	}

	@Test
	void passwordHashVerificationFailureIsUnifiedWithoutNewHashOrMutation() {
		Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		fixture.userPort.byUserId.put(USER_ID, credential(
			USER_ID, "current-password", UserCredentialStatus.ACTIVE, fixture.hasher));
		fixture.hasher.matchesFailure = true;

		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
				USER_ID, "current-password", "new-password")));

		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertEquals(1, fixture.hasher.supportsCalls);
		assertEquals(1, fixture.hasher.matchesCalls);
		assertEquals(0, fixture.hasher.hashCalls);
		assertEquals(0, fixture.userPort.updateCalls);
		assertEquals(0, fixture.sessionStore.revokeCalls);
	}

	@Test
	void absentLockedClosedUnsupportedAndDamagedCredentialsShareInvalidCredentials() {
		for (UserCredentialStatus status : List.of(UserCredentialStatus.LOCKED, UserCredentialStatus.CLOSED)) {
			Fixture fixture = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
			fixture.userPort.byUserId.put(USER_ID, credential(USER_ID, "current-password", status, fixture.hasher));
			assertInvalidCredentials(fixture);
			assertEquals(0, fixture.hasher.matchesCalls);
			assertEquals(0, fixture.hasher.hashCalls);
		}

		Fixture absent = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		assertInvalidCredentials(absent);

		Fixture unsupported = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		unsupported.userPort.byUserId.put(USER_ID, new UserCredential(USER_ID, "unsupported", 2, UserCredentialStatus.ACTIVE));
		assertInvalidCredentials(unsupported);
		assertEquals(1, unsupported.hasher.supportsCalls);
		assertEquals(0, unsupported.hasher.matchesCalls);

		Fixture damaged = fixture(EmailChallengePurpose.RESET_PASSWORD, NOW);
		damaged.userPort.byUserId.put(USER_ID, new UserCredential(USER_ID, "damaged", 1, UserCredentialStatus.ACTIVE));
		damaged.hasher.matchesFailure = true;
		assertInvalidCredentials(damaged);
		assertEquals(1, damaged.hasher.supportsCalls);
		assertEquals(0, damaged.hasher.matchesCalls);
		assertEquals(0, damaged.hasher.hashCalls);
	}

	@Test
	void sensitiveValuesNeverAppearInCommandsExceptionsOrResults() {
		PasswordResetCommand reset = new PasswordResetCommand(EMAIL, "123456", "new-password");
		AuthenticatedPasswordChangeCommand change = new AuthenticatedPasswordChangeCommand(
			USER_ID, "current-password", "new-password");
		assertFalse(reset.toString().contains("new-password"));
		assertFalse(reset.toString().contains("123456"));
		assertFalse(change.toString().contains("current-password"));
		assertFalse(change.toString().contains("new-password"));

		PasswordManagementValidationException validation = new PasswordManagementValidationException();
		assertFalse(validation.getMessage().contains(EMAIL));
		assertFalse(validation.getMessage().contains("123456"));
		assertFalse(validation.getMessage().contains("new-password"));
	}

	private static void assertInvalidCredentials(Fixture fixture) {
		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.changePassword(new AuthenticatedPasswordChangeCommand(
				USER_ID, "current-password", "new-password")));
		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertFalse(failure.getMessage().contains("current-password"));
		assertFalse(failure.getMessage().contains("new-password"));
		assertEquals(0, fixture.userPort.updateCalls);
	}

	private static PasswordResetCommand command(String code) {
		return new PasswordResetCommand(EMAIL, code, "new-password");
	}

	private static Fixture fixture(EmailChallengePurpose purpose, Instant challengeCreatedAt) {
		DirectTransactionRunner transactions = new DirectTransactionRunner();
		FakeChallengeStore challengeStore = new FakeChallengeStore();
		challengeStore.challenge = EmailChallenge.issue(
			UUID.randomUUID(), purpose, EMAIL, "challenge-hash", challengeCreatedAt);
		EmailChallengeApplicationService challengeService = new EmailChallengeApplicationService(
			transactions, challengeStore, new PermittedRateLimitStore(), () -> "123456",
			new FakeChallengeCodeHasher(), (id, challengePurpose, code) -> null, event -> { },
			Clock.fixed(NOW, ZoneOffset.UTC));
		FakePasswordHasher hasher = new FakePasswordHasher();
		FakeUserPasswordPort userPort = new FakeUserPasswordPort();
		FakeSessionStore sessionStore = new FakeSessionStore();
		DeviceSessionApplicationService deviceSessionService = new DeviceSessionApplicationService(
			transactions, sessionStore, new FakeAccessTokenService(), new java.security.SecureRandom(),
			Clock.fixed(NOW, ZoneOffset.UTC));
		PasswordManagementApplicationService service = new PasswordManagementApplicationService(
			transactions, challengeService, hasher, userPort, deviceSessionService,
			Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(service, transactions, challengeStore, hasher, userPort, sessionStore);
	}

	private static UserCredential credential(
		UUID userId, String password, UserCredentialStatus status, FakePasswordHasher hasher) {
		return new UserCredential(userId, "$argon2id$fake-" + password.length(), 1, status);
	}

	private static DeviceSession session(UUID userId, String deviceId, String deviceName, Instant issuedAt) {
		return DeviceSession.create(UUID.randomUUID(), userId, DeviceId.ofNullable(deviceId), DeviceName.of(deviceName), issuedAt);
	}

	private record Fixture(
		PasswordManagementApplicationService service,
		DirectTransactionRunner transactions,
		FakeChallengeStore challengeStore,
		FakePasswordHasher hasher,
		FakeUserPasswordPort userPort,
		FakeSessionStore sessionStore) {
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		private int calls;

		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			calls++;
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			calls++;
			action.run();
		}
	}

	private static final class FakePasswordHasher implements PasswordHasher {
		private int hashCalls;
		private int supportsCalls;
		private int matchesCalls;
		private boolean matchesResult = true;
		private boolean matchesFailure;

		@Override
		public String hash(String password) {
			hashCalls++;
			return "$argon2id$fake-" + password.length();
		}

		@Override
		public boolean supports(int hashVersion, String encodedHash) {
			supportsCalls++;
			return hashVersion == 1 && encodedHash != null && encodedHash.startsWith("$argon2id$fake-");
		}

		@Override
		public boolean matches(String password, String encodedHash) {
			matchesCalls++;
			if (matchesFailure) {
				throw new PasswordHashingException(new IllegalStateException("test hash failure"));
			}
			return matchesResult;
		}
	}

	private static final class FakeUserPasswordPort implements UserPasswordManagementPort {
		private final Map<String, UserCredential> byEmail = new HashMap<>();
		private final Map<UUID, UserCredential> byUserId = new HashMap<>();
		private int calls;
		private int updateCalls;
		private String lastNormalizedEmail;

		@Override
		public Optional<UserCredential> findByUserIdForUpdate(UUID userId) {
			calls++;
			return Optional.ofNullable(byUserId.get(userId));
		}

		@Override
		public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
			calls++;
			lastNormalizedEmail = emailNormalized;
			return Optional.ofNullable(byEmail.get(emailNormalized));
		}

		@Override
		public Optional<UUID> updatePasswordByNormalizedEmail(String emailNormalized, String passwordHash, Instant updatedAt) {
			calls++;
			lastNormalizedEmail = emailNormalized;
			UserCredential credential = byEmail.get(emailNormalized);
			if (credential == null) {
				return Optional.empty();
			}
			UserCredential updated = new UserCredential(
				credential.userId(), passwordHash, 1, credential.status());
			byEmail.put(emailNormalized, updated);
			byUserId.put(credential.userId(), updated);
			updateCalls++;
			return Optional.of(credential.userId());
		}

		@Override
		public void updatePasswordForUser(UUID userId, String passwordHash, Instant updatedAt) {
			calls++;
			updateCalls++;
			UserCredential credential = byUserId.get(userId);
			if (credential == null) {
				credential = byEmail.values().stream()
					.filter(value -> value.userId().equals(userId))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("missing test user"));
			}
			UserCredential updated = new UserCredential(userId, passwordHash, 1, credential.status());
			byUserId.put(userId, updated);
			byEmail.replaceAll((email, value) -> value.userId().equals(userId) ? updated : value);
		}
	}

	private static final class FakeSessionStore implements DeviceSessionStore {
		private final List<DeviceSession> activeSessions = new ArrayList<>();
		private final List<SessionRevocationReason> revokeReasons = new ArrayList<>();
		private int revokeCalls;

		@Override
		public void revokeActiveSessionForReplacement(UUID userId, String deviceId, Instant revokedAt) {
		}

		@Override
		public void insertSession(DeviceSession session) {
			activeSessions.add(session);
		}

		@Override
		public void insertRefreshToken(StoredRefreshToken refreshToken) {
		}

		@Override
		public Optional<RefreshTokenSessionState> findRefreshTokenForUpdate(String tokenHash) {
			return Optional.empty();
		}

		@Override
		public Optional<DeviceSession> findSessionForUserForUpdate(UUID userId, UUID sessionId) {
			return activeSessions.stream().filter(session -> session.id().equals(sessionId)
				&& session.userId().equals(userId)).findFirst();
		}

		@Override
		public List<DeviceSession> findActiveSessionsForUserForUpdate(UUID userId) {
			return activeSessions.stream().filter(session -> session.userId().equals(userId)
				&& session.revokedAt() == null).toList();
		}

		@Override
		public boolean revokeSession(UUID sessionId, Instant revokedAt, SessionRevocationReason reason) {
			boolean exists = activeSessions.stream().anyMatch(session -> session.id().equals(sessionId)
				&& session.revokedAt() == null);
			if (exists) {
				revokeCalls++;
				revokeReasons.add(reason);
			}
			return exists;
		}

		@Override
		public void revokeCurrentRefreshTokens(UUID sessionId, Instant revokedAt) {
		}

		@Override
		public boolean consumeRefreshToken(UUID tokenId, Instant consumedAt) {
			return false;
		}

		@Override
		public boolean linkReplacement(UUID tokenId, UUID replacementTokenId) {
			return false;
		}

		@Override
		public boolean updateLastSeen(UUID sessionId, Instant lastSeenAt) {
			return false;
		}
	}

	private static final class FakeChallengeCodeHasher implements ChallengeCodeHasher {
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

	private static final class FakeChallengeStore implements EmailChallengeStore {
		private EmailChallenge challenge;
		private int failedAttempts;

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
				&& challenge.emailNormalized().equals(normalizedEmail)
				? Optional.of(challenge) : Optional.empty();
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
			failedAttempts++;
			challenge = challenge.recordFailedAttemptAt(now);
			return true;
		}
	}

	private static final class PermittedRateLimitStore implements AuthRateLimitStore {
		@Override
		public RateLimitDecision consume(EmailChallengePurpose purpose, AuthRateLimitSubjects subjects, Instant now) {
			return RateLimitDecision.permitted();
		}

		@Override
		public RateLimitDecision consumeLogin(String normalizedEmail, SourceAddress sourceAddress, Instant now) {
			return RateLimitDecision.permitted();
		}
	}

	private static final class FakeAccessTokenService implements AccessTokenService {
		@Override
		public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant sessionExpiresAt) {
			return new IssuedAccessToken("access", sessionExpiresAt);
		}

		@Override
		public VerifiedAccessToken verify(String encodedToken, Instant now) {
			return null;
		}
	}
}
