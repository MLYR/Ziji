package app.ziji.auth.application;

import java.security.SecureRandom;
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
import app.ziji.auth.domain.RefreshToken;
import app.ziji.auth.domain.RefreshTokenHash;
import app.ziji.auth.domain.StoredRefreshToken;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话应用编排测试：输入边界、稳定替换、固定期限、正常轮换及安全拒绝不产生旁路写入。 */
class DeviceSessionApplicationServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Test
	void creationNormalizesNamePreservesRawDeviceIdAndUsesThirtyDaySession() {
		FakeStore store = new FakeStore();
		DeviceSessionApplicationService service = service(store, NOW);
		UUID userId = UUID.randomUUID();

		SessionTokenResult result = service.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "  ＭａｃＢｏｏｋ　 ", "  opaque-id  "));

		DeviceSession session = store.sessions.get(result.sessionId());
		assertEquals("MacBook", result.deviceName());
		assertEquals("  opaque-id  ", result.deviceId());
		assertEquals(NOW.plusSeconds(30L * 24 * 60 * 60), result.expiresAt());
		assertEquals(NOW, result.lastSeenAt());
		assertEquals(1, store.replacementRequests.size());
		assertEquals("  opaque-id  ", store.replacementRequests.get(0).deviceId());
		assertTrue(result.refreshToken().matches("rt1_[A-Za-z0-9_-]{43}"));
		assertTrue(store.tokens.values().iterator().next().tokenHash().matches("v1:[0-9a-f]{64}"));
		assertFalse(store.tokens.values().iterator().next().tokenHash().contains(result.refreshToken()));
		assertEquals(result.sessionId(), session.id());
	}

	@Test
	void absentDeviceIdCreatesIndependentSessionWithoutReplacement() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();

		SessionTokenResult first = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPhone", null));
		SessionTokenResult second = service(store, NOW.plusSeconds(1)).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPhone", null));

		assertNotEquals(first.sessionId(), second.sessionId());
		assertEquals(2, store.sessions.size());
		assertEquals(0, store.replacementRequests.size());
	}

	@Test
	void invalidDeviceInputIsRejectedBeforeAnyStoreMutation() {
		FakeStore store = new FakeStore();
		DeviceSessionApplicationService service = service(store, NOW);
		UUID userId = UUID.randomUUID();

		assertThrows(SessionTokenValidationException.class,
			() -> service.createForAuthenticatedUser(new CreateDeviceSessionCommand(null, "iPhone", null)));
		assertThrows(SessionTokenValidationException.class,
			() -> service.createForAuthenticatedUser(new CreateDeviceSessionCommand(userId, " ", null)));
		assertThrows(SessionTokenValidationException.class,
			() -> service.createForAuthenticatedUser(new CreateDeviceSessionCommand(userId, "iPhone", "\u3000")));
		assertThrows(SessionTokenValidationException.class,
			() -> service.createForAuthenticatedUser(new CreateDeviceSessionCommand(userId, "x".repeat(101), null)));
		assertThrows(SessionTokenValidationException.class,
			() -> service.createForAuthenticatedUser(new CreateDeviceSessionCommand(userId, "iPhone", "x".repeat(201))));

		assertEquals(0, store.sessions.size());
		assertEquals(0, store.tokens.size());
		assertEquals(0, store.replacementRequests.size());
	}

	@Test
	void normalRotationConsumesOldTokenCreatesSameSessionTokenAndKeepsMonotonicLastSeen() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		SessionTokenResult initial = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPad", "device-a"));
		Instant refreshAt = NOW.plusSeconds(90);

		SessionTokenResult rotated = service(store, refreshAt).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));

		StoredRefreshToken oldToken = findByHash(store, initial.refreshToken());
		StoredRefreshToken newToken = findByHash(store, rotated.refreshToken());
		assertEquals(initial.sessionId(), rotated.sessionId());
		assertEquals(initial.expiresAt(), rotated.expiresAt());
		assertEquals(refreshAt, rotated.lastSeenAt());
		assertEquals(refreshAt, oldToken.consumedAt());
		assertEquals(newToken.id(), oldToken.replacedById());
		assertEquals(initial.sessionId(), newToken.sessionId());
		assertEquals(initial.expiresAt(), newToken.expiresAt());
		assertEquals(newToken.issuedAt(), newToken.createdAt());
		assertNotEquals(initial.refreshToken(), rotated.refreshToken());

		SessionTokenResult nonRegressing = service(store, NOW.plusSeconds(30)).rotate(
			new RotateRefreshTokenCommand(rotated.refreshToken()));
		assertEquals(refreshAt, nonRegressing.lastSeenAt());
	}

	@Test
	void consumedRevokedAndExpiredTokensRejectWithoutSessionMutation() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		SessionTokenResult initial = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Android", "device-b"));
		SessionTokenResult rotated = service(store, NOW.plusSeconds(1)).rotate(
			new RotateRefreshTokenCommand(initial.refreshToken()));
		Instant lastSeenAfterRotation = store.sessions.get(initial.sessionId()).lastSeenAt();

		RefreshTokenRejectedException consumed = assertThrows(RefreshTokenRejectedException.class,
			() -> service(store, NOW.plusSeconds(2)).rotate(new RotateRefreshTokenCommand(initial.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.CONSUMED, consumed.reason());

		StoredRefreshToken current = findByHash(store, rotated.refreshToken());
		store.replaceToken(StoredRefreshToken.restore(current.id(), current.sessionId(),
			RefreshTokenHash.restore(current.tokenHash()), current.issuedAt(), current.expiresAt(), current.consumedAt(),
			NOW.plusSeconds(2), current.replacedById(), current.createdAt()));
		RefreshTokenRejectedException revoked = assertThrows(RefreshTokenRejectedException.class,
			() -> service(store, NOW.plusSeconds(3)).rotate(new RotateRefreshTokenCommand(rotated.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.REVOKED, revoked.reason());

		SessionTokenResult expiredInitial = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(UUID.randomUUID(), "Browser", "device-c"));
		RefreshTokenRejectedException expired = assertThrows(RefreshTokenRejectedException.class,
			() -> service(store, expiredInitial.expiresAt()).rotate(new RotateRefreshTokenCommand(expiredInitial.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.EXPIRED, expired.reason());
		assertEquals(lastSeenAfterRotation, store.sessions.get(initial.sessionId()).lastSeenAt());
	}

	@Test
	void transportNeutralResultDoesNotExposeCredentialsThroughToString() {
		FakeStore store = new FakeStore();
		SessionTokenResult result = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(UUID.randomUUID(), "Desktop", "device-d"));

		assertFalse(result.toString().contains(result.accessToken()));
		assertFalse(result.toString().contains(result.refreshToken()));
	}

	private static StoredRefreshToken findByHash(FakeStore store, String rawToken) {
		String hash = RefreshTokenHash.from(RefreshToken.fromClient(rawToken)).value();
		return store.tokensByHash.get(hash);
	}

	private static DeviceSessionApplicationService service(FakeStore store, Instant now) {
		return new DeviceSessionApplicationService(
			new DirectTransactionRunner(), store, new FakeAccessTokenService(), new SecureRandom(),
			Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
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

	private static final class FakeAccessTokenService implements AccessTokenService {
		@Override
		public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant sessionExpiresAt) {
			return new IssuedAccessToken("access-token-" + sessionId, issuedAt.plusSeconds(1800));
		}

		@Override
		public VerifiedAccessToken verify(String encodedToken, Instant now) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class FakeStore implements DeviceSessionStore {
		private final Map<UUID, DeviceSession> sessions = new HashMap<>();
		private final Map<UUID, StoredRefreshToken> tokens = new HashMap<>();
		private final Map<String, StoredRefreshToken> tokensByHash = new HashMap<>();
		private final List<ReplacementRequest> replacementRequests = new ArrayList<>();

		@Override
		public void revokeActiveSessionForReplacement(UUID userId, String deviceId, Instant revokedAt) {
			replacementRequests.add(new ReplacementRequest(userId, deviceId, revokedAt));
		}

		@Override
		public void insertSession(DeviceSession session) {
			sessions.put(session.id(), session);
		}

		@Override
		public void insertRefreshToken(StoredRefreshToken refreshToken) {
			tokens.put(refreshToken.id(), refreshToken);
			tokensByHash.put(refreshToken.tokenHash(), refreshToken);
		}

		@Override
		public Optional<RefreshTokenSessionState> findRefreshTokenForUpdate(String tokenHash) {
			StoredRefreshToken token = tokensByHash.get(tokenHash);
			return token == null ? Optional.empty() : Optional.of(new RefreshTokenSessionState(
				sessions.get(token.sessionId()), token));
		}

		@Override
		public boolean consumeRefreshToken(UUID tokenId, Instant consumedAt) {
			StoredRefreshToken token = tokens.get(tokenId);
			if (token == null || token.consumedAt() != null || token.revokedAt() != null) {
				return false;
			}
			replaceToken(StoredRefreshToken.restore(token.id(), token.sessionId(), RefreshTokenHash.restore(token.tokenHash()),
				token.issuedAt(), token.expiresAt(), consumedAt, token.revokedAt(), token.replacedById(), token.createdAt()));
			return true;
		}

		@Override
		public boolean linkReplacement(UUID tokenId, UUID replacementTokenId) {
			StoredRefreshToken token = tokens.get(tokenId);
			if (token == null || token.consumedAt() == null || token.replacedById() != null) {
				return false;
			}
			replaceToken(StoredRefreshToken.restore(token.id(), token.sessionId(), RefreshTokenHash.restore(token.tokenHash()),
				token.issuedAt(), token.expiresAt(), token.consumedAt(), token.revokedAt(), replacementTokenId,
				token.createdAt()));
			return true;
		}

		@Override
		public boolean updateLastSeen(UUID sessionId, Instant lastSeenAt) {
			DeviceSession session = sessions.get(sessionId);
			if (session == null || session.revokedAt() != null || !lastSeenAt.isBefore(session.expiresAt())
				|| lastSeenAt.isBefore(session.lastSeenAt())) {
				return false;
			}
			sessions.put(sessionId, DeviceSession.restore(session.id(), session.userId(),
				DeviceId.ofNullable(session.deviceId()), DeviceName.of(session.deviceName()), session.issuedAt(),
				session.expiresAt(), session.revokedAt(), session.revokeReason(), lastSeenAt));
			return true;
		}

		private void replaceToken(StoredRefreshToken token) {
			tokens.put(token.id(), token);
			tokensByHash.put(token.tokenHash(), token);
		}
	}

	private record ReplacementRequest(UUID userId, String deviceId, Instant revokedAt) {
	}
}
