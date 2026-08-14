package app.ziji;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V011 数据库基线：稳定会话与刷新 Token 的绝对期限、轮换关系和历史兼容性。 */
@Testcontainers
class SessionTokenSecurityBaselineMigrationTests {

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
		new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName("ziji_session_token_baseline")
			.withUsername("ziji")
			.withPassword("ziji-test");

	@Test
	void v011PreservesLegacyRowsAndRejectsUnsafeNewSessionTokenStates() throws Exception {
		migrateTo("10");
		OffsetDateTime issuedAt = OffsetDateTime.parse("2026-08-14T00:00:00Z");
		UUID userId = UUID.randomUUID();
		UUID legacyRevokedSessionId = UUID.randomUUID();
		UUID legacyActiveSessionId = UUID.randomUUID();
		UUID legacyTokenId = UUID.randomUUID();
		try (Connection connection = connection()) {
			insertUser(connection, userId, issuedAt);
			// V010 允许的旧撤销记录没有原因；V011 必须保留，而不能伪造历史安全结论。
			insertSession(connection, legacyRevokedSessionId, userId, null, null, issuedAt,
				issuedAt.plusDays(7), issuedAt.plusHours(1), null, issuedAt);
			insertSession(connection, legacyActiveSessionId, userId, "legacy-device", null, issuedAt,
				issuedAt.plusDays(7), null, null, issuedAt);
			insertToken(connection, legacyTokenId, legacyActiveSessionId, "legacy-token-hash", issuedAt,
				issuedAt.plusDays(7), null, null, null, issuedAt);
		}

		migrateTo(null);

		try (Connection connection = connection()) {
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM user_sessions WHERE revoke_reason IS NULL"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM user_sessions WHERE security_baseline_version IS NULL"));
			updateSessionRevocation(connection, legacyActiveSessionId, issuedAt.plusDays(8), "SECURITY_ADMIN");
			consumeToken(connection, legacyTokenId, issuedAt.plusDays(8));
			revokeToken(connection, legacyTokenId, issuedAt.plusDays(8));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM user_sessions
				WHERE id = '%s' AND revoked_at IS NOT NULL AND revoke_reason = 'SECURITY_ADMIN'
				""".formatted(legacyActiveSessionId)));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM session_refresh_tokens
				WHERE id = '%s' AND consumed_at IS NOT NULL AND revoked_at IS NOT NULL
				""".formatted(legacyTokenId)));
			assertRejected(() -> updateSessionUser(connection, legacyActiveSessionId, UUID.randomUUID()));
			assertRejected(() -> updateSessionExpiresAt(connection, legacyActiveSessionId, issuedAt.plusDays(30)));
			assertRejected(() -> updateTokenHash(connection, legacyTokenId, tokenHash(99)));
			assertRejected(() -> updateTokenSession(connection, legacyTokenId, UUID.randomUUID()));
			UUID validSessionId = UUID.randomUUID();
			OffsetDateTime expiresAt = issuedAt.plusHours(720);
			insertSession(connection, validSessionId, userId, "  ios-opaque-device  ", "iPhone 17", issuedAt,
				expiresAt, null, null, issuedAt);
			UUID validTokenId = UUID.randomUUID();
			insertToken(connection, validTokenId, validSessionId, tokenHash(1), issuedAt,
				expiresAt, null, null, null, issuedAt);
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM user_sessions
				WHERE id = '%s' AND device_id = '  ios-opaque-device  ' AND security_baseline_version = 1
				""".formatted(validSessionId)));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM session_refresh_tokens
				WHERE id = '%s' AND created_at = issued_at AND security_baseline_version = 1
				""".formatted(validTokenId)));
			insertSession(connection, UUID.randomUUID(), userId, null, "iPad", issuedAt.plusDays(9),
				issuedAt.plusDays(39), null, null, issuedAt.plusDays(9));
			UUID rotationSessionId = createSession(connection, userId, issuedAt.plusDays(1), 11);
			UUID currentTokenId = UUID.randomUUID();
			OffsetDateTime rotationIssuedAt = issuedAt.plusDays(1);
			OffsetDateTime rotationAt = rotationIssuedAt.plusMinutes(5);
			insertToken(connection, currentTokenId, rotationSessionId, tokenHash(11), rotationIssuedAt,
				rotationIssuedAt.plusHours(720), null, null, null, rotationIssuedAt);
			rotateToken(connection, currentTokenId, UUID.randomUUID(), rotationSessionId, tokenHash(12), rotationAt,
				rotationIssuedAt.plusHours(720));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM session_refresh_tokens
				WHERE session_id = '%s' AND consumed_at IS NOT NULL AND replaced_by_id IS NOT NULL
				""".formatted(rotationSessionId)));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM session_refresh_tokens
				WHERE session_id = '%s' AND consumed_at IS NULL AND revoked_at IS NULL
				""".formatted(rotationSessionId)));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM user_sessions
				WHERE id = '%s' AND last_seen_at = TIMESTAMPTZ '%s'
				""".formatted(rotationSessionId, rotationAt)));

			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "device", "Pixel", issuedAt,
				issuedAt.plusHours(719), null, null, issuedAt));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "device", "", issuedAt,
				expiresAt, null, null, issuedAt));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "", "Pixel", issuedAt,
				expiresAt, null, null, issuedAt));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, " \t ", "Pixel", issuedAt,
				expiresAt, null, null, issuedAt));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "device", "Pixel", issuedAt,
				expiresAt, issuedAt.plusHours(1), null, issuedAt));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "device", "Pixel", issuedAt,
				expiresAt, null, null, issuedAt.minusSeconds(1)));
			assertRejected(() -> insertSession(connection, UUID.randomUUID(), userId, "device", "Pixel", issuedAt,
				expiresAt, issuedAt.plusHours(1), "UNKNOWN_REASON", issuedAt));

			UUID hashSessionId = createSession(connection, userId, issuedAt.plusDays(1), 10);
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), hashSessionId, "v1:ABC", issuedAt.plusDays(1),
				issuedAt.plusDays(31), null, null, null, issuedAt.plusDays(1)));
			UUID lifetimeSessionId = createSession(connection, userId, issuedAt.plusDays(2), 20);
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), lifetimeSessionId, tokenHash(2), issuedAt.plusDays(2),
				issuedAt.plusDays(32).minusSeconds(1), null, null, null, issuedAt.plusDays(2)));
			UUID timeSessionId = createSession(connection, userId, issuedAt.plusDays(3), 30);
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), timeSessionId, tokenHash(3), issuedAt.plusDays(3).minusSeconds(1),
				issuedAt.plusDays(33), null, null, null, issuedAt.plusDays(3)));
			UUID createdAtSessionId = createSession(connection, userId, issuedAt.plusDays(7), 70);
			OffsetDateTime createdAtIssued = issuedAt.plusDays(7);
			OffsetDateTime createdAtExpires = createdAtIssued.plusHours(720);
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), createdAtSessionId, tokenHash(7), createdAtIssued,
				createdAtExpires, null, null, null, createdAtIssued.minusSeconds(1)));
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), createdAtSessionId, tokenHash(8), createdAtIssued,
				createdAtExpires, null, null, null, createdAtExpires));
			assertRejected(() -> insertToken(connection, UUID.randomUUID(), createdAtSessionId, tokenHash(9), createdAtIssued,
				createdAtExpires, null, null, null, createdAtExpires.plusSeconds(1)));

			UUID selfTokenId = UUID.randomUUID();
			UUID selfSessionId = createSession(connection, userId, issuedAt.plusDays(4), 40);
			insertToken(connection, selfTokenId, selfSessionId, tokenHash(4), issuedAt.plusDays(4),
				issuedAt.plusDays(34), null, null, null, issuedAt.plusDays(4));
			assertRejected(() -> updateReplacement(connection, selfTokenId, selfTokenId, issuedAt.plusDays(4).plusHours(1)));

			UUID firstSessionId = createSession(connection, userId, issuedAt.plusDays(5), 50);
			UUID secondSessionId = createSession(connection, userId, issuedAt.plusDays(6), 60);
			UUID firstTokenId = UUID.randomUUID();
			UUID secondTokenId = UUID.randomUUID();
			insertToken(connection, firstTokenId, firstSessionId, tokenHash(5), issuedAt.plusDays(5),
				issuedAt.plusDays(35), null, null, null, issuedAt.plusDays(5));
			insertToken(connection, secondTokenId, secondSessionId, tokenHash(6), issuedAt.plusDays(6),
				issuedAt.plusDays(36), null, null, null, issuedAt.plusDays(6));
			assertRejected(() -> updateReplacement(connection, firstTokenId, secondTokenId, issuedAt.plusDays(5).plusHours(1)));
		}
	}

	private static void migrateTo(String target) {
		var configuration = Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static UUID createSession(Connection connection, UUID userId, OffsetDateTime issuedAt, int hashSeed) throws SQLException {
		UUID sessionId = UUID.randomUUID();
		insertSession(connection, sessionId, userId, "device-" + hashSeed, "Device " + hashSeed, issuedAt,
			issuedAt.plusHours(720), null, null, issuedAt);
		return sessionId;
	}

	private static void insertUser(Connection connection, UUID userId, OffsetDateTime now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '会话测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""")) {
			statement.setObject(1, userId);
			statement.setString(2, "session-baseline@example.test");
			statement.setString(3, "session-baseline@example.test");
			statement.setString(4, now.toString());
			statement.setString(5, "$argon2id$test");
			statement.setString(6, now.toString());
			statement.setString(7, now.toString());
			statement.executeUpdate();
		}
	}

	private static void insertSession(
		Connection connection,
		UUID sessionId,
		UUID userId,
		String deviceId,
		String deviceName,
		OffsetDateTime issuedAt,
		OffsetDateTime expiresAt,
		OffsetDateTime revokedAt,
		String revokeReason,
		OffsetDateTime lastSeenAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO user_sessions (
				id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at
			) VALUES (?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""")) {
			statement.setObject(1, sessionId);
			statement.setObject(2, userId);
			statement.setString(3, deviceId);
			statement.setString(4, deviceName);
			statement.setString(5, issuedAt.toString());
			statement.setString(6, expiresAt.toString());
			statement.setString(7, revokedAt == null ? null : revokedAt.toString());
			statement.setString(8, revokeReason);
			statement.setString(9, lastSeenAt.toString());
			statement.executeUpdate();
		}
	}

	private static void insertToken(
		Connection connection,
		UUID tokenId,
		UUID sessionId,
		String tokenHash,
		OffsetDateTime issuedAt,
		OffsetDateTime expiresAt,
		OffsetDateTime consumedAt,
		OffsetDateTime revokedAt,
		UUID replacedById,
		OffsetDateTime createdAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO session_refresh_tokens (
				id, session_id, token_hash, issued_at, expires_at, consumed_at, revoked_at, replaced_by_id, created_at
			) VALUES (?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz),
				CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""")) {
			statement.setObject(1, tokenId);
			statement.setObject(2, sessionId);
			statement.setString(3, tokenHash);
			statement.setString(4, issuedAt.toString());
			statement.setString(5, expiresAt.toString());
			statement.setString(6, consumedAt == null ? null : consumedAt.toString());
			statement.setString(7, revokedAt == null ? null : revokedAt.toString());
			statement.setObject(8, replacedById);
			statement.setString(9, createdAt.toString());
			statement.executeUpdate();
		}
	}

	private static void updateReplacement(
		Connection connection, UUID tokenId, UUID replacementId, OffsetDateTime consumedAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			UPDATE session_refresh_tokens
			SET consumed_at = CAST(? AS timestamptz), replaced_by_id = ?
			WHERE id = ?
			""")) {
			statement.setString(1, consumedAt.toString());
			statement.setObject(2, replacementId);
			statement.setObject(3, tokenId);
			statement.executeUpdate();
		}
	}

	private static void updateSessionRevocation(
		Connection connection, UUID sessionId, OffsetDateTime revokedAt, String revokeReason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			UPDATE user_sessions SET revoked_at = CAST(? AS timestamptz), revoke_reason = ? WHERE id = ?
			""")) {
			statement.setString(1, revokedAt.toString());
			statement.setString(2, revokeReason);
			statement.setObject(3, sessionId);
			statement.executeUpdate();
		}
	}

	private static void consumeToken(Connection connection, UUID tokenId, OffsetDateTime consumedAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			UPDATE session_refresh_tokens SET consumed_at = CAST(? AS timestamptz) WHERE id = ?
			""")) {
			statement.setString(1, consumedAt.toString());
			statement.setObject(2, tokenId);
			statement.executeUpdate();
		}
	}

	private static void revokeToken(Connection connection, UUID tokenId, OffsetDateTime revokedAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			UPDATE session_refresh_tokens SET revoked_at = CAST(? AS timestamptz) WHERE id = ?
			""")) {
			statement.setString(1, revokedAt.toString());
			statement.setObject(2, tokenId);
			statement.executeUpdate();
		}
	}

	// 历史行只允许终态安全处置；以下辅助方法证明无法借该路径改写身份、期限或凭据事实。
	private static void updateSessionUser(Connection connection, UUID sessionId, UUID userId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE user_sessions SET user_id = ? WHERE id = ?")) {
			statement.setObject(1, userId);
			statement.setObject(2, sessionId);
			statement.executeUpdate();
		}
	}

	private static void updateSessionExpiresAt(
		Connection connection, UUID sessionId, OffsetDateTime expiresAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			UPDATE user_sessions SET expires_at = CAST(? AS timestamptz) WHERE id = ?
			""")) {
			statement.setString(1, expiresAt.toString());
			statement.setObject(2, sessionId);
			statement.executeUpdate();
		}
	}

	private static void updateTokenHash(Connection connection, UUID tokenId, String tokenHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE session_refresh_tokens SET token_hash = ? WHERE id = ?")) {
			statement.setString(1, tokenHash);
			statement.setObject(2, tokenId);
			statement.executeUpdate();
		}
	}

	private static void updateTokenSession(Connection connection, UUID tokenId, UUID sessionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE session_refresh_tokens SET session_id = ? WHERE id = ?")) {
			statement.setObject(1, sessionId);
			statement.setObject(2, tokenId);
			statement.executeUpdate();
		}
	}

	private static void rotateToken(
		Connection connection,
		UUID currentTokenId,
		UUID nextTokenId,
		UUID sessionId,
		String nextTokenHash,
		OffsetDateTime rotatedAt,
		OffsetDateTime expiresAt) throws SQLException {
		connection.setAutoCommit(false);
		try {
			// 先消费旧 Token 才能释放同 session 的当前 Token 部分唯一索引，后续关系在同一提交点校验。
			updateReplacement(connection, currentTokenId, null, rotatedAt);
			insertToken(connection, nextTokenId, sessionId, nextTokenHash, rotatedAt, expiresAt,
				null, null, null, rotatedAt);
			updateReplacement(connection, currentTokenId, nextTokenId, rotatedAt);
			try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE user_sessions SET last_seen_at = CAST(? AS timestamptz) WHERE id = ?
				""")) {
				statement.setString(1, rotatedAt.toString());
				statement.setObject(2, sessionId);
				statement.executeUpdate();
			}
			connection.commit();
		} catch (SQLException exception) {
			connection.rollback();
			throw exception;
		} finally {
			connection.setAutoCommit(true);
		}
	}

	private static void assertRejected(SqlAction action) {
		SQLException exception = assertThrows(SQLException.class, action::run);
		assertEquals("23514", exception.getSQLState());
	}

	private static int count(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static String tokenHash(int value) {
		return "v1:" + String.format("%064x", value);
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}
}
