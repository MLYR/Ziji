package app.ziji.auth.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialLookupPort;
import app.ziji.user.application.UserCredentialStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码登录应用编排测试：格式校验先于限流和 Argon2、登录限流独立于验证码、统一失败语义、单次 Argon2id 防时序枚举，
 * 以及限流拒绝短路凭据查询与 Hash。
 */
class PasswordLoginApplicationServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final SourceAddress SOURCE = SourceAddress.parseLiteral("192.0.2.30");
	private static final String EMAIL = "login-user@example.com";

	@Test
	void activeAndClosingUsersWithCorrectPasswordAuthenticate() {
		Fixture active = fixture();
		UserCredential activeUser = seed(active, "Correct-Pass-1", UserCredentialStatus.ACTIVE);
		PasswordLoginResult activeResult = active.service.login(command(EMAIL, "Correct-Pass-1"));
		assertEquals(activeUser.userId(), activeResult.userId());
		assertEquals(UserCredentialStatus.ACTIVE, activeResult.status());
		assertEquals(1, active.hasher.matchesCalls);
		assertEquals(1, active.store.loginCalls);
		assertEquals(0, active.store.consumeCalls);

		Fixture closing = fixture();
		seed(closing, "Correct-Pass-2", UserCredentialStatus.CLOSING);
		PasswordLoginResult closingResult = closing.service.login(command(EMAIL, "Correct-Pass-2"));
		assertEquals(UserCredentialStatus.CLOSING, closingResult.status());
		assertEquals(1, closing.hasher.matchesCalls);
	}

	@Test
	void wrongPasswordFailsWithInvalidCredentials() {
		Fixture fixture = fixture();
		seed(fixture, "Correct-Pass-1", UserCredentialStatus.ACTIVE);

		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command(EMAIL, "Wrong-Pass-999")));

		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertEquals(1, fixture.hasher.matchesCalls);
		assertLeakProof(failure, EMAIL);
	}

	@Test
	void lockedAndClosedUsersFailEvenWithCorrectPasswordButStillVerifyOnce() {
		Fixture locked = fixture();
		seed(locked, "Correct-Pass-1", UserCredentialStatus.LOCKED);
		assertThrows(InvalidCredentialsException.class,
			() -> locked.service.login(command(EMAIL, "Correct-Pass-1")));
		assertEquals(1, locked.hasher.matchesCalls);

		Fixture closed = fixture();
		seed(closed, "Correct-Pass-1", UserCredentialStatus.CLOSED);
		assertThrows(InvalidCredentialsException.class,
			() -> closed.service.login(command(EMAIL, "Correct-Pass-1")));
		assertEquals(1, closed.hasher.matchesCalls);
	}

	@Test
	void absentUserRunsDummyArgon2OnceAndFails() {
		Fixture fixture = fixture();

		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command("absent@example.com", "Any-Pass-1")));

		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertEquals(1, fixture.hasher.matchesCalls);
		// 不存在用户只校验 dummy Hash 一次，且使用服务生命周期内的固定 dummy。
		assertEquals(1, fixture.hasher.matchedHashes.size());
		assertEquals(fixture.dummy, fixture.hasher.matchedHashes.get(0));
		assertEquals(1, fixture.port.calls);
	}

	@Test
	void unsupportedHashVersionAndStructurallyBrokenArgon2idFallBackToDummyAndUnify() {
		Fixture unsupported = fixture();
		unsupported.port.byEmail.put(EMAIL, new UserCredential(
			UUID.randomUUID(), unsupported.hasher.hash("stored-but-v2"), 2, UserCredentialStatus.ACTIVE));

		assertThrows(InvalidCredentialsException.class,
			() -> unsupported.service.login(command(EMAIL, "stored-but-v2")));
		assertEquals(1, unsupported.hasher.matchesCalls);
		assertEquals(unsupported.dummy, unsupported.hasher.matchedHashes.get(0));

		Fixture corrupt = fixture();
		String brokenHash = "$argon2id$broken";
		corrupt.port.byEmail.put(EMAIL, new UserCredential(
			UUID.randomUUID(), brokenHash, 1, UserCredentialStatus.ACTIVE));
		InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
			() -> corrupt.service.login(command(EMAIL, "whatever")));
		assertEquals(1, corrupt.hasher.matchesCalls);
		assertEquals(corrupt.dummy, corrupt.hasher.matchedHashes.get(0));
		assertFalse(corrupt.hasher.matchedHashes.contains(brokenHash));
		assertEquals("INVALID_CREDENTIALS", failure.code());
		assertLeakProof(failure, EMAIL);
		assertFalse(failure.getMessage().contains(UserCredentialStatus.ACTIVE.name()));
		assertFalse(failure.getMessage().contains(brokenHash));
	}

	@Test
	void allCredentialFailuresShareUniformCodeMessageAndLeakNothing() {
		Fixture fixture = fixture();
		UserCredential active = seed(fixture, "Correct-Pass-1", UserCredentialStatus.ACTIVE);

		List<InvalidCredentialsException> failures = new ArrayList<>();
		failures.add(assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command(EMAIL, "Wrong-Pass-999"))));
		failures.add(assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command("absent@example.com", "Any-Pass-1"))));

		UserCredential locked = new UserCredential(
			active.userId(), active.passwordHash(), 1, UserCredentialStatus.LOCKED);
		fixture.port.byEmail.put(EMAIL, locked);
		failures.add(assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command(EMAIL, "Correct-Pass-1"))));

		fixture.port.byEmail.put(EMAIL, new UserCredential(
			active.userId(), active.passwordHash(), 1, UserCredentialStatus.CLOSED));
		failures.add(assertThrows(InvalidCredentialsException.class,
			() -> fixture.service.login(command(EMAIL, "Correct-Pass-1"))));

		String firstMessage = failures.get(0).getMessage();
		for (InvalidCredentialsException failure : failures) {
			assertEquals("INVALID_CREDENTIALS", failure.code());
			assertEquals(firstMessage, failure.getMessage());
			assertLeakProof(failure, EMAIL);
			assertFalse(failure.getMessage().contains(active.userId().toString()));
			assertFalse(Arrays.stream(failure.getStackTrace())
				.anyMatch(frame -> frame.getMethodName().contains("password")));
		}
		assertEquals(4, fixture.hasher.matchesCalls);
	}

	@Test
	void dummyHashIsInitializedOncePerServiceLifecycle() {
		FakePasswordHasher hasher = new FakePasswordHasher();
		FakeCredentialLookupPort port = new FakeCredentialLookupPort();
		FakeRateLimitStore store = new FakeRateLimitStore();
		PasswordLoginApplicationService service = new PasswordLoginApplicationService(
			new DirectTransactionRunner(), store, port, hasher);

		assertEquals(1, hasher.producedHashes.size());
		String dummy = hasher.producedHashes.get(0);

		for (int attempt = 0; attempt < 3; attempt++) {
			String absentEmail = "absent-" + attempt + "@example.com";
			assertThrows(InvalidCredentialsException.class,
				() -> service.login(command(absentEmail, "Any-Pass-1")));
		}
		// 多次登录不会重复生成 dummy，且都复用同一 dummy。
		assertEquals(1, hasher.producedHashes.size());
		assertEquals(3, hasher.matchedHashes.size());
		assertTrue(hasher.matchedHashes.stream().allMatch(dummy::equals));
	}

	@Test
	void invalidFormatFailsBeforeRateLimitAndHash() {
		Fixture fixture = fixture();

		PasswordLoginValidationException nullCommand = assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(null));
		assertEquals("VALIDATION_ERROR", nullCommand.code());
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand(EMAIL, null, SOURCE, NOW)));
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand(EMAIL, "", SOURCE, NOW)));
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand(EMAIL, "x".repeat(129), SOURCE, NOW)));
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand(EMAIL, "valid-pass", null, NOW)));
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand(EMAIL, "valid-pass", SOURCE, null)));
		assertThrows(PasswordLoginValidationException.class,
			() -> fixture.service.login(new PasswordLoginCommand("not-an-email", "valid-pass", SOURCE, NOW)));

		assertEquals(0, fixture.store.loginCalls);
		assertEquals(0, fixture.hasher.matchesCalls);
		assertEquals(0, fixture.port.calls);
	}

	@Test
	void rateLimitDeniedShortCircuitsCredentialLookupAndHashAndPropagatesRetryAfter() {
		Fixture fixture = fixture();
		seed(fixture, "Correct-Pass-1", UserCredentialStatus.ACTIVE);
		fixture.store.loginDecision = RateLimitDecision.denied(42);

		LoginRateLimitedException limited = assertThrows(LoginRateLimitedException.class,
			() -> fixture.service.login(command(EMAIL, "Correct-Pass-1")));

		assertEquals("RATE_LIMITED", limited.code());
		assertEquals(42, limited.retryAfterSeconds());
		assertEquals(1, fixture.store.loginCalls);
		assertEquals(0, fixture.port.calls);
		assertEquals(0, fixture.hasher.matchesCalls);
	}

	@Test
	void successfulResultExposesOnlyUserIdAndStatus() {
		Fixture fixture = fixture();
		UserCredential user = seed(fixture, "Correct-Pass-1", UserCredentialStatus.ACTIVE);

		PasswordLoginResult result = fixture.service.login(command(EMAIL, "Correct-Pass-1"));

		assertEquals(user.userId(), result.userId());
		assertEquals(UserCredentialStatus.ACTIVE, result.status());
		List<String> fields = Arrays.stream(PasswordLoginResult.class.getDeclaredFields())
			.map(field -> field.getName().toLowerCase(java.util.Locale.ROOT))
			.toList();
		assertTrue(fields.containsAll(List.of("userid", "status")));
		assertFalse(fields.stream().anyMatch(name -> name.contains("password") || name.contains("hash")
			|| name.contains("token") || name.contains("cookie") || name.contains("session")));
		assertFalse(result.toString().contains("Correct-Pass-1"));
	}

	private static UserCredential seed(Fixture fixture, String password, UserCredentialStatus status) {
		String storedHash = fixture.hasher.hash(password);
		UserCredential credential = new UserCredential(UUID.randomUUID(), storedHash, 1, status);
		fixture.port.byEmail.put(EMAIL, credential);
		return credential;
	}

	private static void assertLeakProof(Throwable failure, String email) {
		assertFalse(failure.getMessage().contains(email));
		assertFalse(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("passwordhashversion"));
	}

	private static PasswordLoginCommand command(String email, String password) {
		return new PasswordLoginCommand(email, password, SOURCE, NOW);
	}

	private static Fixture fixture() {
		FakePasswordHasher hasher = new FakePasswordHasher();
		FakeCredentialLookupPort port = new FakeCredentialLookupPort();
		FakeRateLimitStore store = new FakeRateLimitStore();
		PasswordLoginApplicationService service = new PasswordLoginApplicationService(
			new DirectTransactionRunner(), store, port, hasher);
		String dummy = hasher.producedHashes.get(0);
		return new Fixture(hasher, port, store, service, dummy);
	}

	private record Fixture(
		FakePasswordHasher hasher,
		FakeCredentialLookupPort port,
		FakeRateLimitStore store,
		PasswordLoginApplicationService service,
		String dummy) {
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

	private static final class FakeRateLimitStore implements AuthRateLimitStore {
		private int consumeCalls;
		private int loginCalls;
		private RateLimitDecision loginDecision = RateLimitDecision.permitted();

		@Override
		public RateLimitDecision consume(
			EmailChallengePurpose purpose, AuthRateLimitSubjects subjects, Instant now) {
			consumeCalls++;
			return RateLimitDecision.permitted();
		}

		@Override
		public RateLimitDecision consumeLogin(String normalizedEmail, SourceAddress sourceAddress, Instant now) {
			loginCalls++;
			return loginDecision;
		}
	}

	private static final class FakeCredentialLookupPort implements UserCredentialLookupPort {
		private final Map<String, UserCredential> byEmail = new HashMap<>();
		private int calls;

		@Override
		public Optional<UserCredential> findByNormalizedEmail(String emailNormalized) {
			calls++;
			return Optional.ofNullable(byEmail.get(emailNormalized));
		}

		@Override
		public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
			calls++;
			return Optional.ofNullable(byEmail.get(emailNormalized));
		}
	}

	private static final class FakePasswordHasher implements PasswordHasher {
		private final Map<String, String> passwordById = new HashMap<>();
		private final List<String> producedHashes = new ArrayList<>();
		private final List<String> matchedHashes = new ArrayList<>();
		private int matchesCalls;
		private int nextId = 1000;

		@Override
		public String hash(String password) {
			String id = "d" + (nextId++);
			passwordById.put(id, password);
			String encoded = "$argon2id$fake$" + id;
			producedHashes.add(encoded);
			return encoded;
		}

		@Override
		public boolean supports(int hashVersion, String encodedHash) {
			return hashVersion == 1 && encodedHash != null && encodedHash.startsWith("$argon2id$fake$");
		}

		@Override
		public boolean matches(String password, String encodedHash) {
			matchesCalls++;
			matchedHashes.add(encodedHash);
			if (encodedHash == null || !encodedHash.startsWith("$argon2id$fake$")) {
				return false;
			}
			String id = encodedHash.substring("$argon2id$fake$".length());
			return password.equals(passwordById.get(id));
		}
	}
}
