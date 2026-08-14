package app.ziji;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V010 数据库基线：验证从 V009 升级后只接受冻结的验证码和密码登录限流组合。 */
@Testcontainers
class PasswordLoginSecurityBaselineMigrationTests {

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
		new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName("ziji_login_baseline")
			.withUsername("ziji")
			.withPassword("ziji-test");

	@Test
	void v010PreservesChallengeBucketsAndRejectsInvalidLoginCombinations() throws Exception {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target("9")
			.load()
			.migrate();

		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		OffsetDateTime challengeStart = aligned(now, 60);
		try (Connection connection = connection()) {
			insertBucket(connection, "SEND_EMAIL_CHALLENGE", "REGISTER", "EMAIL", "AUTH_CHALLENGE_V1",
				"EMAIL_60S", 60, 1, challengeStart, hash((byte) 1));
		}

		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = connection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM auth_rate_limit_buckets"));

			insertBucket(connection, "SEND_EMAIL_CHALLENGE", "RESET_PASSWORD", "DEVICE", "AUTH_CHALLENGE_V1",
				"DEVICE_1H", 3600, 10, aligned(now, 3600), hash((byte) 2));
			insertBucket(connection, "LOGIN_PASSWORD", "LOGIN", "IP", "AUTH_LOGIN_V1",
				"LOGIN_IP_10M", 600, 30, aligned(now, 600), hash((byte) 3));
			insertBucket(connection, "LOGIN_PASSWORD", "LOGIN", "IP", "AUTH_LOGIN_V1",
				"LOGIN_IP_24H", 86400, 300, aligned(now, 86400), hash((byte) 4));
			insertBucket(connection, "LOGIN_PASSWORD", "LOGIN", "EMAIL", "AUTH_LOGIN_V1",
				"LOGIN_EMAIL_15M", 900, 10, aligned(now, 900), hash((byte) 5));
			insertBucket(connection, "LOGIN_PASSWORD", "LOGIN", "EMAIL", "AUTH_LOGIN_V1",
				"LOGIN_EMAIL_24H", 86400, 50, aligned(now, 86400), hash((byte) 6));

			assertRejected(connection, "LOGIN_PASSWORD", "REGISTER", "EMAIL", "AUTH_LOGIN_V1",
				"LOGIN_EMAIL_15M", 900, 10, hash((byte) 10));
			assertRejected(connection, "SEND_EMAIL_CHALLENGE", "LOGIN", "EMAIL", "AUTH_CHALLENGE_V1",
				"EMAIL_60S", 60, 1, hash((byte) 11));
			assertRejected(connection, "LOGIN_PASSWORD", "LOGIN", "DEVICE", "AUTH_LOGIN_V1",
				"DEVICE_1H", 3600, 10, hash((byte) 12));
			assertRejected(connection, "LOGIN_PASSWORD", "LOGIN", "IP", "AUTH_LOGIN_V1",
				"IP_10M", 600, 20, hash((byte) 13));
			assertRejected(connection, "SEND_EMAIL_CHALLENGE", "REGISTER", "IP", "AUTH_CHALLENGE_V1",
				"LOGIN_IP_10M", 600, 30, hash((byte) 14));
			assertRejected(connection, "LOGIN_PASSWORD", "LOGIN", "IP", "AUTH_LOGIN_V1",
				"LOGIN_IP_10M", 599, 30, hash((byte) 15));
			assertRejected(connection, "LOGIN_PASSWORD", "LOGIN", "EMAIL", "AUTH_LOGIN_V1",
				"LOGIN_EMAIL_15M", 900, 9, hash((byte) 16));
			assertRejected(connection, "LOGIN_PASSWORD", "LOGIN", "EMAIL", "AUTH_CHALLENGE_V1",
				"LOGIN_EMAIL_15M", 900, 10, hash((byte) 17));

			SQLException duplicate = assertThrows(SQLException.class, () -> insertBucket(connection,
				"SEND_EMAIL_CHALLENGE", "REGISTER", "EMAIL", "AUTH_CHALLENGE_V1",
				"EMAIL_60S", 60, 1, challengeStart, hash((byte) 1)));
			assertEquals("23505", duplicate.getSQLState());

			OffsetDateTime unaligned = aligned(now, 600).plusSeconds(1);
			SQLException alignment = assertThrows(SQLException.class, () -> insertBucket(connection,
				"LOGIN_PASSWORD", "LOGIN", "IP", "AUTH_LOGIN_V1",
				"LOGIN_IP_10M", 600, 30, unaligned, hash((byte) 18)));
			assertEquals("23514", alignment.getSQLState());

			SQLException retention = assertThrows(SQLException.class, () -> execute(connection,
				"DELETE FROM auth_rate_limit_buckets WHERE subject_hash = ?", hash((byte) 3)));
			assertEquals("23514", retention.getSQLState());
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static void assertRejected(
		Connection connection,
		String action,
		String purpose,
		String dimension,
		String policy,
		String windowCode,
		int windowSeconds,
		int limitCount,
		byte[] subjectHash) {
		assertEquals("23514", assertThrows(SQLException.class, () -> insertBucket(connection,
			action, purpose, dimension, policy, windowCode, windowSeconds, limitCount,
			aligned(OffsetDateTime.now(ZoneOffset.UTC), Math.max(1, windowSeconds)), subjectHash)).getSQLState());
	}

	private static void insertBucket(
		Connection connection,
		String action,
		String purpose,
		String dimension,
		String policy,
		String windowCode,
		int windowSeconds,
		int limitCount,
		OffsetDateTime windowStartedAt,
		byte[] subjectHash) throws SQLException {
		String sql = """
			INSERT INTO auth_rate_limit_buckets
				(id, action, purpose, dimension, subject_hash, hash_key_version, policy_code,
				 window_code, window_seconds, limit_count, window_started_at, window_ends_at,
				 request_count, blocked_until, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz),
				1, NULL, CAST(? AS timestamptz), CAST(? AS timestamptz))
			""";
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		OffsetDateTime windowEndsAt = windowStartedAt.plusSeconds(windowSeconds);
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, UUID.randomUUID());
			statement.setString(2, action);
			statement.setString(3, purpose);
			statement.setString(4, dimension);
			statement.setBytes(5, subjectHash);
			statement.setString(6, policy);
			statement.setString(7, windowCode);
			statement.setInt(8, windowSeconds);
			statement.setInt(9, limitCount);
			statement.setString(10, windowStartedAt.toString());
			statement.setString(11, windowEndsAt.toString());
			statement.setString(12, now.toString());
			statement.setString(13, now.toString());
			statement.executeUpdate();
		}
	}

	private static void execute(Connection connection, String sql, byte[] subjectHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setBytes(1, subjectHash);
			statement.executeUpdate();
		}
	}

	private static int count(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static OffsetDateTime aligned(OffsetDateTime now, int seconds) {
		long epochSecond = now.toInstant().getEpochSecond();
		return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, seconds)).atOffset(ZoneOffset.UTC);
	}

	private static byte[] hash(byte value) {
		byte[] hash = new byte[32];
		hash[0] = value;
		return hash;
	}
}
