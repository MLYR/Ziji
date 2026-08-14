package app.ziji.auth.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.RefreshToken;
import app.ziji.auth.domain.RefreshTokenHash;
import app.ziji.auth.domain.SessionRevocationReason;
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

	@Test
	void consumedTokenReuseRevokesOnlyItsSessionAndAllCurrentTokens() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		SessionTokenResult compromised = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "reuse-device"));
		SessionTokenResult unaffected = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Tablet", "unaffected-device"));
		SessionTokenResult rotated = service(store, NOW.plusSeconds(1)).rotate(
			new RotateRefreshTokenCommand(compromised.refreshToken()));

		RefreshTokenReuseResult result = service(store, NOW.plusSeconds(2)).handleConsumedRefreshTokenReuse(
			new RotateRefreshTokenCommand(compromised.refreshToken()));

		assertEquals(RefreshTokenReuseResult.Status.REVOKED, result.status());
		assertEquals("REFRESH_TOKEN_REUSE", store.sessions.get(compromised.sessionId()).revokeReason());
		assertNull(findByHash(store, compromised.refreshToken()).revokedAt());
		assertEquals(NOW.plusSeconds(2), findByHash(store, rotated.refreshToken()).revokedAt());
		assertNull(store.sessions.get(unaffected.sessionId()).revokedAt());
		assertNull(findByHash(store, unaffected.refreshToken()).revokedAt());
	}

	@Test
	void invalidUnknownOrdinaryRevokedAndExpiredTokensDoNotTriggerReuseDisposition() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		SessionTokenResult current = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Desktop", "reject-device"));
		SessionTokenResult expired = service(store, NOW.minusSeconds(DeviceSession.ABSOLUTE_LIFETIME.toSeconds() + 1))
			.createForAuthenticatedUser(new CreateDeviceSessionCommand(UUID.randomUUID(), "Old", "expired-device"));
		StoredRefreshToken currentToken = findByHash(store, current.refreshToken());
		store.replaceToken(StoredRefreshToken.restore(currentToken.id(), currentToken.sessionId(),
			RefreshTokenHash.restore(currentToken.tokenHash()), currentToken.issuedAt(), currentToken.expiresAt(),
			currentToken.consumedAt(), NOW, currentToken.replacedById(), currentToken.createdAt()));

		assertEquals(RefreshTokenReuseResult.Status.NOT_REUSED,
			service(store, NOW).handleConsumedRefreshTokenReuse(new RotateRefreshTokenCommand("rt1_bad")).status());
		assertEquals(RefreshTokenReuseResult.Status.NOT_REUSED,
			service(store, NOW).handleConsumedRefreshTokenReuse(
				new RotateRefreshTokenCommand(RefreshToken.generate(new SecureRandom()).value())).status());
		assertEquals(RefreshTokenReuseResult.Status.NOT_REUSED,
			service(store, NOW).handleConsumedRefreshTokenReuse(new RotateRefreshTokenCommand(current.refreshToken())).status());
		assertEquals(RefreshTokenReuseResult.Status.NOT_REUSED,
			service(store, NOW).handleConsumedRefreshTokenReuse(new RotateRefreshTokenCommand(expired.refreshToken())).status());

		assertNull(store.sessions.get(current.sessionId()).revokedAt());
		assertEquals(NOW, findByHash(store, current.refreshToken()).revokedAt());
		assertNull(store.sessions.get(expired.sessionId()).revokedAt());
	}

	@Test
	void consumedTokenReuseAfterExpiryStillRevokesItsSession() {
		FakeStore store = new FakeStore();
		Instant issuedAt = NOW.minus(DeviceSession.ABSOLUTE_LIFETIME).plusSeconds(1);
		SessionTokenResult initial = service(store, issuedAt).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(UUID.randomUUID(), "Short-lived", "expired-reuse-device"));
		service(store, NOW).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));

		RefreshTokenReuseResult result = service(store, NOW.plusSeconds(2)).handleConsumedRefreshTokenReuse(
			new RotateRefreshTokenCommand(initial.refreshToken()));

		assertEquals(RefreshTokenReuseResult.Status.REVOKED, result.status());
		assertEquals("REFRESH_TOKEN_REUSE", store.sessions.get(initial.sessionId()).revokeReason());
	}

	@Test
	void currentSelectedAndAllDeviceRevocationsUseTheirRequestedReasons() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		SessionTokenResult current = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "current-device"));
		SessionTokenResult selected = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Tablet", "selected-device"));
		SessionTokenResult all = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Browser", "all-device"));

		assertEquals(SessionRevocationResult.Status.REVOKED,
			service(store, NOW.plusSeconds(1)).revokeCurrentDevice(userId, current.sessionId()).status());
		assertEquals("CURRENT_DEVICE", store.sessions.get(current.sessionId()).revokeReason());
		assertEquals(SessionRevocationResult.Status.REVOKED,
			service(store, NOW.plusSeconds(2)).revokeSelectedDevice(userId, selected.sessionId()).status());
		assertEquals("SELECTED_DEVICE", store.sessions.get(selected.sessionId()).revokeReason());
		SessionRevocationResult allResult = service(store, NOW.plusSeconds(3)).revokeAllDevices(userId);
		assertEquals(SessionRevocationResult.Status.REVOKED, allResult.status());
		assertEquals(1, allResult.revokedSessionCount());
		assertEquals("ALL_DEVICES", store.sessions.get(all.sessionId()).revokeReason());
		assertEquals(NOW.plusSeconds(1), findByHash(store, current.refreshToken()).revokedAt());
		assertEquals(NOW.plusSeconds(2), findByHash(store, selected.refreshToken()).revokedAt());
		assertEquals(NOW.plusSeconds(3), findByHash(store, all.refreshToken()).revokedAt());
	}

	@Test
	void otherUsersCannotRevokeSessionAndRepeatedRevocationIsIdempotent() {
		FakeStore store = new FakeStore();
		UUID ownerId = UUID.randomUUID();
		SessionTokenResult owned = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(ownerId, "Phone", "owner-device"));

		assertEquals(SessionRevocationResult.Status.NOT_FOUND,
			service(store, NOW.plusSeconds(1)).revokeSelectedDevice(UUID.randomUUID(), owned.sessionId()).status());
		assertNull(store.sessions.get(owned.sessionId()).revokedAt());
		assertNull(findByHash(store, owned.refreshToken()).revokedAt());

		assertEquals(SessionRevocationResult.Status.REVOKED,
			service(store, NOW.plusSeconds(2)).revokeCurrentDevice(ownerId, owned.sessionId()).status());
		Instant revokedAt = store.sessions.get(owned.sessionId()).revokedAt();
		assertEquals(SessionRevocationResult.Status.ALREADY_REVOKED,
			service(store, NOW.plusSeconds(3)).revokeCurrentDevice(ownerId, owned.sessionId()).status());
		assertEquals(revokedAt, store.sessions.get(owned.sessionId()).revokedAt());
		assertEquals("CURRENT_DEVICE", store.sessions.get(owned.sessionId()).revokeReason());
	}

	@Test
	void legacyNullBaselineUsesSecurityAdminWithoutChangingHistoricalFacts() {
		FakeStore store = new FakeStore();
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Instant issuedAt = NOW.minusSeconds(10_000);
		Instant expiresAt = issuedAt.plusSeconds(7 * 24 * 60 * 60L);
		DeviceSession legacy = DeviceSession.restore(
			sessionId, userId, " legacy-device ", null, issuedAt, expiresAt, null, null, issuedAt, null);
		RefreshToken token = RefreshToken.generate(new SecureRandom());
		store.insertSession(legacy);
		store.insertRefreshToken(StoredRefreshToken.issue(
			UUID.randomUUID(), sessionId, RefreshTokenHash.from(token), issuedAt, expiresAt));

		SessionRevocationResult result = service(store, NOW).revokeCurrentDevice(userId, sessionId);
		DeviceSession revoked = store.sessions.get(sessionId);

		assertEquals(SessionRevocationResult.Status.REVOKED, result.status());
		assertEquals("SECURITY_ADMIN", revoked.revokeReason());
		assertNull(revoked.securityBaselineVersion());
		assertEquals(" legacy-device ", revoked.deviceId());
		assertNull(revoked.deviceName());
		assertEquals(issuedAt, revoked.issuedAt());
		assertEquals(expiresAt, revoked.expiresAt());
		assertEquals(issuedAt, revoked.lastSeenAt());
		assertEquals(NOW, findByHash(store, token.value()).revokedAt());
	}

	@Test
	void securityResultsAndExceptionsDoNotExposeRefreshTokenOrHash() {
		FakeStore store = new FakeStore();
		SessionTokenResult initial = service(store, NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(UUID.randomUUID(), "Secure", "secret-device"));
		SessionTokenResult rotated = service(store, NOW.plusSeconds(1)).rotate(
			new RotateRefreshTokenCommand(initial.refreshToken()));
		String hash = RefreshTokenHash.from(RefreshToken.fromClient(initial.refreshToken())).value();
		RefreshTokenRejectedException exception = assertThrows(RefreshTokenRejectedException.class,
			() -> service(store, NOW.plusSeconds(2)).rotate(new RotateRefreshTokenCommand(initial.refreshToken())));
		RefreshTokenReuseResult reuse = service(store, NOW.plusSeconds(2)).handleConsumedRefreshTokenReuse(
			new RotateRefreshTokenCommand(initial.refreshToken()));
		SessionRevocationResult revocation = service(store, NOW.plusSeconds(3)).revokeCurrentDevice(
			store.sessions.get(initial.sessionId()).userId(), initial.sessionId());

		assertFalse(exception.getMessage().contains(initial.refreshToken()));
		assertFalse(exception.getMessage().contains(hash));
		assertFalse(reuse.toString().contains(initial.refreshToken()));
		assertFalse(reuse.toString().contains(hash));
		assertFalse(revocation.toString().contains(initial.refreshToken()));
		assertFalse(revocation.toString().contains(hash));
		assertFalse(rotated.toString().contains(initial.refreshToken()));
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
		public Optional<DeviceSession> findSessionForUserForUpdate(UUID userId, UUID sessionId) {
			DeviceSession session = sessions.get(sessionId);
			return session == null || !session.userId().equals(userId) ? Optional.empty() : Optional.of(session);
		}

		@Override
		public List<DeviceSession> findActiveSessionsForUserForUpdate(UUID userId) {
			return sessions.values().stream()
				.filter(session -> session.userId().equals(userId) && session.revokedAt() == null)
				.sorted(Comparator.comparing(DeviceSession::id))
				.toList();
		}

		@Override
		public boolean revokeSession(UUID sessionId, Instant revokedAt, SessionRevocationReason reason) {
			DeviceSession session = sessions.get(sessionId);
			if (session == null || session.revokedAt() != null) {
				return false;
			}
			// Fake 存储保留历史字段，只模拟持久化端口允许的不可逆撤销状态转换。
			sessions.put(sessionId, DeviceSession.restore(session.id(), session.userId(), session.deviceId(),
				session.deviceName(), session.issuedAt(), session.expiresAt(), revokedAt, reason.name(), session.lastSeenAt(),
				session.securityBaselineVersion()));
			return true;
		}

		@Override
		public void revokeCurrentRefreshTokens(UUID sessionId, Instant revokedAt) {
			for (StoredRefreshToken token : List.copyOf(tokens.values())) {
				if (token.sessionId().equals(sessionId) && token.consumedAt() == null && token.revokedAt() == null) {
					replaceToken(StoredRefreshToken.restore(token.id(), token.sessionId(),
						RefreshTokenHash.restore(token.tokenHash()), token.issuedAt(), token.expiresAt(), token.consumedAt(),
						revokedAt, token.replacedById(), token.createdAt()));
				}
			}
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
			sessions.put(sessionId, DeviceSession.restore(session.id(), session.userId(), session.deviceId(),
				session.deviceName(), session.issuedAt(), session.expiresAt(), session.revokedAt(), session.revokeReason(),
				lastSeenAt, session.securityBaselineVersion()));
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
