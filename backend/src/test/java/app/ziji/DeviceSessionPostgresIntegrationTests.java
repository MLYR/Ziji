package app.ziji;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.RefreshTokenReuseResult;
import app.ziji.auth.application.RefreshTokenRejectedException;
import app.ziji.auth.application.RotateRefreshTokenCommand;
import app.ziji.auth.application.SessionRevocationResult;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.auth.domain.RefreshToken;
import app.ziji.auth.domain.RefreshTokenHash;
import app.ziji.auth.infrastructure.AuthInfrastructureException;
import app.ziji.shared.application.TransactionRunner;
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

/**
 * 稳定会话真实 PostgreSQL 验收：V011 新行、同设备替换、刷新行锁并发、数据库失败回滚和凭据不落库。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceSessionPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private DeviceSessionStore sessionStore;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private SecureRandom secureRandom;

	@BeforeEach
	void clearSessionFacts() {
		jdbc.execute("TRUNCATE TABLE session_refresh_tokens, user_sessions");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'session-token-%@example.test'");
	}

	@AfterEach
	void removeFailureTrigger() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_session_refresh_insert_for_test ON session_refresh_tokens");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_session_refresh_insert_for_test()");
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_session_refresh_revocation_for_test ON session_refresh_tokens");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_session_refresh_revocation_for_test()");
	}

	@Test
	void initialLoginCreatesV011SessionAndStoresOnlyVersionedRefreshHash() {
		UUID userId = seedUser("session-token-initial@example.test");

		SessionTokenResult result = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "  ＭａｃＢｏｏｋ　 ", "  opaque-device  "));

		assertEquals("MacBook", jdbc.queryForObject(
			"SELECT device_name FROM user_sessions WHERE id = ?", String.class, result.sessionId()));
		assertEquals("  opaque-device  ", jdbc.queryForObject(
			"SELECT device_id FROM user_sessions WHERE id = ?", String.class, result.sessionId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT security_baseline_version FROM user_sessions WHERE id = ?", Integer.class, result.sessionId()));
		assertEquals(NOW.plusSeconds(30L * 24 * 60 * 60), instant("""
			SELECT expires_at FROM user_sessions WHERE id = ?
			""", result.sessionId()));
		assertEquals(NOW, instant("SELECT last_seen_at FROM user_sessions WHERE id = ?", result.sessionId()));
		String storedHash = jdbc.queryForObject("""
			SELECT token_hash FROM session_refresh_tokens WHERE session_id = ?
			""", String.class, result.sessionId());
		assertTrue(result.refreshToken().matches("rt1_[A-Za-z0-9_-]{43}"));
		assertTrue(storedHash.matches("v1:[0-9a-f]{64}"));
		assertNotEquals(result.refreshToken(), storedHash);
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE token_hash = ?
			""", Integer.class, result.refreshToken()));
		assertFalse(result.toString().contains(result.refreshToken()));
		assertFalse(result.toString().contains(result.accessToken()));
	}

	@Test
	void sameNonEmptyDeviceIdAtomicallyRevokesOldSessionAndCurrentRefreshToken() {
		UUID userId = seedUser("session-token-replace@example.test");
		SessionTokenResult first = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPhone", "stable-device"));
		SessionTokenResult second = serviceAt(NOW.plusSeconds(5)).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPhone", "stable-device"));

		assertNotEquals(first.sessionId(), second.sessionId());
		assertEquals("REPLACED_BY_LOGIN", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, first.sessionId()));
		assertEquals(NOW.plusSeconds(5), instant("SELECT revoked_at FROM user_sessions WHERE id = ?", first.sessionId()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND revoked_at IS NOT NULL
			""", Integer.class, first.sessionId()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
			""", Integer.class, userId, "stable-device"));
	}

	@Test
	void missingDeviceIdCreatesIndependentActiveSessions() {
		UUID userId = seedUser("session-token-null-device@example.test");
		SessionTokenResult first = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPad", null));
		SessionTokenResult second = serviceAt(NOW.plusSeconds(1)).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "iPad", null));

		assertNotEquals(first.sessionId(), second.sessionId());
		assertEquals(2, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE user_id = ? AND device_id IS NULL AND revoked_at IS NULL
			""", Integer.class, userId));
	}

	@Test
	void normalRotationUsesOneSessionAndFixedExpiryAndUpdatesLastSeen() {
		UUID userId = seedUser("session-token-rotate@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Browser", "browser-device"));
		Instant rotateAt = NOW.plusSeconds(120);

		SessionTokenResult rotated = serviceAt(rotateAt).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));

		assertEquals(initial.sessionId(), rotated.sessionId());
		assertEquals(initial.expiresAt(), rotated.expiresAt());
		assertEquals(rotateAt, rotated.lastSeenAt());
		assertEquals(rotateAt, instant("SELECT last_seen_at FROM user_sessions WHERE id = ?", initial.sessionId()));
		UUID oldTokenId = tokenId(initial.refreshToken());
		UUID newTokenId = tokenId(rotated.refreshToken());
		assertEquals(newTokenId, jdbc.queryForObject("""
			SELECT replaced_by_id FROM session_refresh_tokens WHERE id = ?
			""", UUID.class, oldTokenId));
		assertEquals(rotateAt, instant("SELECT consumed_at FROM session_refresh_tokens WHERE id = ?", oldTokenId));
		assertEquals(initial.sessionId(), jdbc.queryForObject("""
			SELECT session_id FROM session_refresh_tokens WHERE id = ?
			""", UUID.class, newTokenId));
		assertEquals(initial.expiresAt(), instant("SELECT expires_at FROM session_refresh_tokens WHERE id = ?", newTokenId));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE id = ? AND created_at = issued_at
			""", Integer.class, newTokenId));
	}

	@Test
	void databaseFailureDuringRotationRollsBackConsumptionReplacementAndLastSeen() {
		UUID userId = seedUser("session-token-rollback@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Desktop", "desktop-device"));
		UUID oldTokenId = tokenId(initial.refreshToken());
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_session_refresh_insert_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				RAISE EXCEPTION 'test-only refresh insert failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_session_refresh_insert_for_test
			BEFORE INSERT ON session_refresh_tokens
			FOR EACH ROW EXECUTE FUNCTION reject_session_refresh_insert_for_test()
			""");

		assertThrows(AuthInfrastructureException.class,
			() -> serviceAt(NOW.plusSeconds(30)).rotate(new RotateRefreshTokenCommand(initial.refreshToken())));

		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ?
			""", Integer.class, initial.sessionId()));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE id = ? AND consumed_at IS NOT NULL
			""", Integer.class, oldTokenId));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE id = ? AND replaced_by_id IS NOT NULL
			""", Integer.class, oldTokenId));
		assertEquals(NOW, instant("SELECT last_seen_at FROM user_sessions WHERE id = ?", initial.sessionId()));
	}

	@Test
	void replacementInsertFailureRollsBackOldSessionAndTokenRevocation() {
		UUID userId = seedUser("session-token-replace-rollback@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "replace-rollback-device"));
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_session_refresh_insert_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				RAISE EXCEPTION 'test-only refresh insert failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_session_refresh_insert_for_test
			BEFORE INSERT ON session_refresh_tokens
			FOR EACH ROW EXECUTE FUNCTION reject_session_refresh_insert_for_test()
			""");

		assertThrows(AuthInfrastructureException.class, () -> serviceAt(NOW.plusSeconds(10)).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "replace-rollback-device")));

		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
			""", Integer.class, userId, "replace-rollback-device"));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND revoked_at IS NOT NULL
			""", Integer.class, initial.sessionId()));
	}

	@Test
	void concurrentUseOfOneRefreshTokenHasExactlyOneNormalSuccess() throws Exception {
		UUID userId = seedUser("session-token-concurrent@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Android", "android-device"));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int index = 0; index < 2; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						serviceAt(NOW.plusSeconds(30)).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));
						return true;
					} catch (RefreshTokenRejectedException expected) {
						return false;
					}
				}));
			}
			ready.await();
			start.countDown();
			int successes = 0;
			for (Future<Boolean> future : futures) {
				if (future.get()) {
					successes++;
				}
			}
			assertEquals(1, successes);
		} finally {
			executor.shutdownNow();
		}
		assertEquals(2, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ?
			""", Integer.class, initial.sessionId()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, initial.sessionId()));
	}

	@Test
	void consumedRevokedAndExpiredTokensRejectWithoutChangingLastSeen() {
		UUID userId = seedUser("session-token-reject@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Tablet", "tablet-device"));
		SessionTokenResult rotated = serviceAt(NOW.plusSeconds(1)).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));
		Instant lastSeen = instant("SELECT last_seen_at FROM user_sessions WHERE id = ?", initial.sessionId());

		RefreshTokenRejectedException consumed = assertThrows(RefreshTokenRejectedException.class,
			() -> serviceAt(NOW.plusSeconds(2)).rotate(new RotateRefreshTokenCommand(initial.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.CONSUMED, consumed.reason());

		jdbc.update("UPDATE session_refresh_tokens SET revoked_at = CAST(? AS timestamptz) WHERE id = ?",
			NOW.plusSeconds(2).toString(), tokenId(rotated.refreshToken()));
		RefreshTokenRejectedException revoked = assertThrows(RefreshTokenRejectedException.class,
			() -> serviceAt(NOW.plusSeconds(3)).rotate(new RotateRefreshTokenCommand(rotated.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.REVOKED, revoked.reason());

		SessionTokenResult expiryCandidate = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(seedUser("session-token-expiry@example.test"), "Another", "expiry-device"));
		RefreshTokenRejectedException expired = assertThrows(RefreshTokenRejectedException.class,
			() -> serviceAt(expiryCandidate.expiresAt()).rotate(new RotateRefreshTokenCommand(expiryCandidate.refreshToken())));
		assertEquals(RefreshTokenRejectedException.Reason.EXPIRED, expired.reason());
		assertEquals(lastSeen, instant("SELECT last_seen_at FROM user_sessions WHERE id = ?", initial.sessionId()));
	}

	@Test
	void consumedRefreshTokenReuseRevokesOnlyOwningSessionAndItsCurrentToken() {
		UUID userId = seedUser("session-token-reuse@example.test");
		SessionTokenResult compromised = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "reuse-device"));
		SessionTokenResult unaffected = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Tablet", "unaffected-device"));
		SessionTokenResult rotated = serviceAt(NOW.plusSeconds(1)).rotate(
			new RotateRefreshTokenCommand(compromised.refreshToken()));

		RefreshTokenReuseResult result = serviceAt(NOW.plusSeconds(2)).handleConsumedRefreshTokenReuse(
			new RotateRefreshTokenCommand(compromised.refreshToken()));

		assertEquals(RefreshTokenReuseResult.Status.REVOKED, result.status());
		assertEquals("REFRESH_TOKEN_REUSE", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, compromised.sessionId()));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, compromised.sessionId()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE id = ? AND consumed_at IS NOT NULL AND revoked_at IS NULL
			""", Integer.class, tokenId(compromised.refreshToken())));
		assertEquals(NOW.plusSeconds(2), instant("SELECT revoked_at FROM session_refresh_tokens WHERE id = ?", tokenId(rotated.refreshToken())));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NULL
			""", Integer.class, unaffected.sessionId()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, unaffected.sessionId()));
	}

	@Test
	void currentSelectedAndAllRevocationsUseExpectedReasons() {
		UUID userId = seedUser("session-token-device-revoke@example.test");
		SessionTokenResult current = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "current-device"));
		SessionTokenResult selected = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Tablet", "selected-device"));
		SessionTokenResult all = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Browser", "all-device"));

		assertEquals(SessionRevocationResult.Status.REVOKED,
			serviceAt(NOW.plusSeconds(1)).revokeCurrentDevice(userId, current.sessionId()).status());
		assertEquals("CURRENT_DEVICE", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, current.sessionId()));
		assertEquals(SessionRevocationResult.Status.REVOKED,
			serviceAt(NOW.plusSeconds(2)).revokeSelectedDevice(userId, selected.sessionId()).status());
		assertEquals("SELECTED_DEVICE", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, selected.sessionId()));
		SessionRevocationResult allResult = serviceAt(NOW.plusSeconds(3)).revokeAllDevices(userId);
		assertEquals(SessionRevocationResult.Status.REVOKED, allResult.status());
		assertEquals(1, allResult.revokedSessionCount());
		assertEquals("ALL_DEVICES", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, all.sessionId()));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id IN (?, ?, ?) AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, current.sessionId(), selected.sessionId(), all.sessionId()));
	}

	@Test
	void invisibleOtherUserSessionIsNotMutatedAndRepeatRevocationIsIdempotent() {
		UUID ownerId = seedUser("session-token-owner@example.test");
		SessionTokenResult owned = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(ownerId, "Phone", "owner-device"));

		assertEquals(SessionRevocationResult.Status.NOT_FOUND,
			serviceAt(NOW.plusSeconds(1)).revokeSelectedDevice(UUID.randomUUID(), owned.sessionId()).status());
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL
			""", Integer.class, owned.sessionId()));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND revoked_at IS NOT NULL
			""", Integer.class, owned.sessionId()));

		assertEquals(SessionRevocationResult.Status.REVOKED,
			serviceAt(NOW.plusSeconds(2)).revokeCurrentDevice(ownerId, owned.sessionId()).status());
		Instant revokedAt = instant("SELECT revoked_at FROM user_sessions WHERE id = ?", owned.sessionId());
		assertEquals(SessionRevocationResult.Status.ALREADY_REVOKED,
			serviceAt(NOW.plusSeconds(3)).revokeCurrentDevice(ownerId, owned.sessionId()).status());
		assertEquals(revokedAt, instant("SELECT revoked_at FROM user_sessions WHERE id = ?", owned.sessionId()));
		assertEquals("CURRENT_DEVICE", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, owned.sessionId()));
	}

	@Test
	void concurrentReuseAndRevocationDuringRotationLeaveNoCurrentToken() throws Exception {
		UUID userId = seedUser("session-token-concurrent-revoke@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Phone", "concurrent-device"));
		serviceAt(NOW.plusSeconds(1)).rotate(new RotateRefreshTokenCommand(initial.refreshToken()));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<RefreshTokenReuseResult.Status>> futures = new ArrayList<>();
			for (int index = 0; index < 2; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return serviceAt(NOW.plusSeconds(2)).handleConsumedRefreshTokenReuse(
						new RotateRefreshTokenCommand(initial.refreshToken())).status();
				}));
			}
			ready.await();
			start.countDown();
			int revoked = 0;
			int alreadyRevoked = 0;
			for (Future<RefreshTokenReuseResult.Status> future : futures) {
				RefreshTokenReuseResult.Status status = future.get();
				if (status == RefreshTokenReuseResult.Status.REVOKED) {
					revoked++;
				} else if (status == RefreshTokenReuseResult.Status.ALREADY_REVOKED) {
					alreadyRevoked++;
				}
			}
			assertEquals(1, revoked);
			assertEquals(1, alreadyRevoked);
		} finally {
			executor.shutdownNow();
		}
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, initial.sessionId()));

		UUID raceUserId = seedUser("session-token-race@example.test");
		SessionTokenResult rotating = serviceAt(NOW.plusSeconds(10)).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(raceUserId, "Browser", "race-device"));
		ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
		CountDownLatch raceReady = new CountDownLatch(2);
		CountDownLatch raceStart = new CountDownLatch(1);
		try {
			Future<Void> rotation = raceExecutor.submit(() -> {
				raceReady.countDown();
				raceStart.await();
				try {
					serviceAt(NOW.plusSeconds(11)).rotate(new RotateRefreshTokenCommand(rotating.refreshToken()));
				} catch (RefreshTokenRejectedException expected) {
					// 撤销先获得会话锁时，轮换必须安全拒绝而不是创建新 Token。
				}
				return null;
			});
			Future<SessionRevocationResult> revocation = raceExecutor.submit(() -> {
				raceReady.countDown();
				raceStart.await();
				return serviceAt(NOW.plusSeconds(11)).revokeCurrentDevice(raceUserId, rotating.sessionId());
			});
			raceReady.await();
			raceStart.countDown();
			rotation.get();
			assertEquals(SessionRevocationResult.Status.REVOKED, revocation.get().status());
		} finally {
			raceExecutor.shutdownNow();
		}
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
			""", Integer.class, rotating.sessionId()));
	}

	@Test
	void databaseFailureDuringRevocationRollsBackSessionAndTokenChanges() {
		UUID userId = seedUser("session-token-revoke-rollback@example.test");
		SessionTokenResult initial = serviceAt(NOW).createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "Desktop", "revoke-rollback-device"));
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_session_refresh_revocation_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				IF NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
					RAISE EXCEPTION 'test-only refresh revocation failure';
				END IF;
				RETURN NEW;
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_session_refresh_revocation_for_test
			BEFORE UPDATE ON session_refresh_tokens
			FOR EACH ROW EXECUTE FUNCTION reject_session_refresh_revocation_for_test()
			""");

		assertThrows(AuthInfrastructureException.class,
			() -> serviceAt(NOW.plusSeconds(1)).revokeCurrentDevice(userId, initial.sessionId()));

		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL
			""", Integer.class, initial.sessionId()));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens WHERE session_id = ? AND revoked_at IS NOT NULL
			""", Integer.class, initial.sessionId()));
	}

	@Test
	void legacyNullBaselineIsSafelyRevokedWithSecurityAdminWithoutChangingHistoricalFacts() {
		UUID userId = seedUser("session-token-legacy@example.test");
		UUID sessionId = UUID.randomUUID();
		UUID tokenId = UUID.randomUUID();
		Instant issuedAt = NOW.minusSeconds(3_600);
		Instant expiresAt = NOW.plusSeconds(7 * 24 * 60 * 60L);
		jdbc.execute("ALTER TABLE session_refresh_tokens DISABLE TRIGGER trg_validate_refresh_token_session_window");
		jdbc.execute("ALTER TABLE user_sessions DISABLE TRIGGER trg_validate_user_session_security_baseline");
		try {
			// 模拟 V010 升级留下的历史行：NULL 基线和空设备名称均为既有事实，不经应用伪造为新会话。
			jdbc.update("""
				INSERT INTO user_sessions
					(id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at,
					 security_baseline_version)
				VALUES (?, ?, ' legacy-device ', NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL,
					CAST(? AS timestamptz), NULL)
				""", sessionId, userId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString());
			jdbc.update("""
				INSERT INTO session_refresh_tokens
					(id, session_id, token_hash, issued_at, expires_at, consumed_at, revoked_at, replaced_by_id, created_at,
					 security_baseline_version)
				VALUES (?, ?, 'legacy-token-hash', CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL, NULL,
					CAST(? AS timestamptz), NULL)
				""", tokenId, sessionId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString());
		} finally {
			jdbc.execute("ALTER TABLE user_sessions ENABLE TRIGGER trg_validate_user_session_security_baseline");
			jdbc.execute("ALTER TABLE session_refresh_tokens ENABLE TRIGGER trg_validate_refresh_token_session_window");
		}

		assertEquals(SessionRevocationResult.Status.REVOKED,
			serviceAt(NOW).revokeCurrentDevice(userId, sessionId).status());
		assertEquals("SECURITY_ADMIN", jdbc.queryForObject(
			"SELECT revoke_reason FROM user_sessions WHERE id = ?", String.class, sessionId));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions
			WHERE id = ? AND security_baseline_version IS NULL AND device_id = ' legacy-device ' AND device_name IS NULL
				AND issued_at = CAST(? AS timestamptz) AND expires_at = CAST(? AS timestamptz) AND last_seen_at = CAST(? AS timestamptz)
			""", Integer.class, sessionId, issuedAt.toString(), expiresAt.toString(), issuedAt.toString()));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens
			WHERE id = ? AND token_hash = 'legacy-token-hash' AND security_baseline_version IS NULL AND revoked_at IS NOT NULL
			""", Integer.class, tokenId));
	}

	private DeviceSessionApplicationService serviceAt(Instant now) {
		return new DeviceSessionApplicationService(transactionRunner, sessionStore, accessTokenService, secureRandom,
			Clock.fixed(now, ZoneOffset.UTC));
	}

	private UUID seedUser(String email) {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), '$argon2id$test', 1,
				'会话测试', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE',
				CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, NOW.toString(), NOW.toString(), NOW.toString());
		return userId;
	}

	private UUID tokenId(String refreshToken) {
		String tokenHash = RefreshTokenHash.from(RefreshToken.fromClient(refreshToken)).value();
		return jdbc.queryForObject("""
			SELECT id FROM session_refresh_tokens
			WHERE token_hash = ?
			""", UUID.class, tokenHash);
	}

	private Instant instant(String sql, Object... parameters) {
		OffsetDateTime value = jdbc.queryForObject(sql, OffsetDateTime.class, parameters);
		return value.toInstant();
	}
}
