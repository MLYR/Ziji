package app.ziji;

import java.sql.Connection;
import java.sql.Date;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V009 数据库基线：认证/匿名幂等主体、生命周期、安全重放引用与保留清理边界。 */
@Testcontainers
class IdempotencySecurityBaselineMigrationTests {

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer EMPTY_POSTGRES =
		new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName("ziji_idempotency_empty")
			.withUsername("ziji")
			.withPassword("ziji-test");

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer UPGRADE_POSTGRES =
		new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName("ziji_idempotency_upgrade")
			.withUsername("ziji")
			.withPassword("ziji-test");

	@Test
	void emptyPostgresDatabaseMigratesV001ThroughV009InOrder() throws Exception {
		migrateTo(EMPTY_POSTGRES, "9");

		try (Connection connection = connection(EMPTY_POSTGRES)) {
			assertEquals("001,002,003,004,005,006,007,008,009", stringValue(connection, """
				SELECT string_agg(version, ',' ORDER BY installed_rank)
				FROM flyway_schema_history
				WHERE success
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM flyway_schema_history
				WHERE version = '009' AND success
				"""));
		}
	}

	@Test
	void v009PreservesLegacyRecordsAndEnforcesNewIdempotencySafetyBoundary() throws Exception {
		migrateTo(UPGRADE_POSTGRES, "8");
		UUID userId = UUID.randomUUID();
		UUID legacyId = UUID.randomUUID();
		OffsetDateTime legacyCreatedAt = OffsetDateTime.parse("2026-08-01T00:00:00Z");
		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			insertUser(connection, userId, legacyCreatedAt);
			// 旧行故意使用 V009 前允许的响应引用，升级必须保留，不能为历史伪造安全终态。
			insertLegacyRecord(connection, legacyId, userId, legacyCreatedAt);
		}

		migrateTo(UPGRADE_POSTGRES, "9");

		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			assertLegacyRecordWasPreserved(connection, legacyId);
			assertV009DatabaseObjects(connection);

			OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
			verifyPositiveAuthenticatedAndAnonymousStates(connection, userId, now);
			verifyRejectedScopeLifecycleAndResponseStates(connection, userId, now);
			verifyRetentionAndReferenceProtection(connection, userId, now);
		}
	}

	private static void assertLegacyRecordWasPreserved(Connection connection, UUID legacyId) throws SQLException {
		assertEquals(1, count(connection, """
			SELECT COUNT(*) FROM idempotency_records
			WHERE id = ?
				AND user_id IS NOT NULL
				AND anonymous_subject_hash IS NULL
				AND anonymous_subject_hash_key_version IS NULL
				AND status = 'SUCCEEDED'
				AND response_reference ->> 'legacy' = 'preserved'
				AND expires_at = created_at + interval '7 days'
			""", legacyId));
		assertEquals(2, count(connection, """
			SELECT COUNT(*) FROM pg_constraint
			WHERE conname IN ('ck_idempotency_lifecycle_v1', 'ck_idempotency_response_reference_safe')
				AND NOT convalidated
			"""));
	}

	private static void assertV009DatabaseObjects(Connection connection) throws SQLException {
		assertEquals(4, count(connection, """
			SELECT COUNT(*) FROM pg_indexes
			WHERE schemaname = 'public'
				AND indexname IN (
					'uq_idempotency_authenticated_scope',
					'uq_idempotency_anonymous_scope',
					'idx_idempotency_processing_lease',
					'idx_idempotency_cleanup_candidate'
				)
			"""));
		assertEquals(1, count(connection, """
			SELECT COUNT(*) FROM pg_trigger
			WHERE tgrelid = 'idempotency_records'::regclass
				AND tgname = 'trg_idempotency_record_retention'
				AND NOT tgisinternal
			"""));
		assertEquals(2, count(connection, """
			SELECT COUNT(*) FROM pg_constraint
			WHERE conname IN ('fk_transactions_idempotency', 'fk_sync_operations_idempotency')
				AND contype = 'f'
				AND confrelid = 'idempotency_records'::regclass
			"""));
		String safeResponseConstraint = stringValue(connection, """
			SELECT pg_get_constraintdef(oid) FROM pg_constraint
			WHERE conname = 'ck_idempotency_response_reference_safe'
			""");
		assertTrue(safeResponseConstraint.contains("octet_length((response_reference)::text) <= 8192"));
		assertTrue(safeResponseConstraint.contains("retryAfterSeconds"));
		assertTrue(booleanValue(connection,
			"SELECT has_table_privilege('ziji_app', 'public.idempotency_records', 'DELETE')"));
		assertFalse(booleanValue(connection, """
			SELECT EXISTS (
				SELECT 1 FROM information_schema.role_table_grants
				WHERE table_schema = 'public' AND table_name = 'idempotency_records'
					AND grantee = 'PUBLIC' AND privilege_type = 'DELETE'
			)
			"""));
	}

	private static void verifyPositiveAuthenticatedAndAnonymousStates(
		Connection connection, UUID userId, OffsetDateTime now) throws SQLException {
		UUID resourceId = UUID.randomUUID();
		insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "createAccount", "authenticated-success-key", requestHash(1),
			"SUCCEEDED", 201, resourceReference(resourceId), "ACCOUNT", resourceId,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7)));
		insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), null, hash((byte) 2), 2, "registerUser", "anonymous-current-key", requestHash(2),
			"PROCESSING", null, null, null, null,
			now, null, now, now.plusSeconds(30), null, now.plusDays(7)));
		insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), null, hash((byte) 1), 1, "resetPassword", "anonymous-previous-key", requestHash(3),
			"FAILED_FINAL", 409, problemReference("IDEMPOTENCY_KEY_REUSED"), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7)));
		insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "retryable-key", requestHash(4),
			"FAILED_RETRYABLE", 503, retryableProblemReference(), null, null,
			now, now.plusSeconds(1), null, null, now.plusSeconds(6), now.plusDays(7)));

		assertEquals(4, count(connection, """
			SELECT COUNT(*) FROM idempotency_records
			WHERE status IN ('PROCESSING', 'SUCCEEDED', 'FAILED_FINAL', 'FAILED_RETRYABLE')
				AND id <> (SELECT id FROM idempotency_records WHERE response_reference ->> 'legacy' = 'preserved')
			"""));
		assertEquals(2, count(connection, """
			SELECT COUNT(*) FROM idempotency_records
			WHERE anonymous_subject_hash IS NOT NULL
				AND anonymous_subject_hash_key_version IN (1, 2)
			"""));

		SQLException authenticatedDuplicate = assertThrows(SQLException.class, () -> insertRecord(connection,
			new IdempotencyRecord(UUID.randomUUID(), userId, null, null, "createAccount", "authenticated-success-key",
				requestHash(5), "SUCCEEDED", 200, emptyReference(), null, null,
				now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
		assertEquals("23505", authenticatedDuplicate.getSQLState());
	}

	private static void verifyRejectedScopeLifecycleAndResponseStates(
		Connection connection, UUID userId, OffsetDateTime now) {
		// 两种主体必须互斥，匿名摘要和密钥版本也必须形成完整、固定长度的对。
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, hash((byte) 9), 1, "registerUser", "mixed-subject-key", requestHash(10),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), null, null, null, "registerUser", "missing-subject-key", requestHash(11),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), null, new byte[31], 1, "registerUser", "short-hash-key", requestHash(12),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), null, hash((byte) 3), 0, "registerUser", "zero-version-key", requestHash(13),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));

		// 生命周期、30 秒租约、5 秒重试与最短保留期均由 V009 后的新写入强制执行。
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "short-lease-key", requestHash(14),
			"PROCESSING", null, null, null, null,
			now, null, now, now.plusSeconds(29), null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "wrong-retry-key", requestHash(15),
			"FAILED_RETRYABLE", 503, retryableProblemReference(), null, null,
			now, now.plusSeconds(1), null, null, now.plusSeconds(5), now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "completed-before-created-key", requestHash(16),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.minusSeconds(1), null, null, null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "short-retention-key", requestHash(17),
			"SUCCEEDED", 200, emptyReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(6))));

		UUID oversizedResourceId = UUID.randomUUID();
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "oversized-response-key", requestHash(18),
			"SUCCEEDED", 200, oversizedResourceReference(), "TRANSACTION", oversizedResourceId,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
		assertRejected(() -> insertRecord(connection, new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "unsafe-response-key", requestHash(19),
			"SUCCEEDED", 200, unsafeResponseReference(), null, null,
			now, now.plusSeconds(1), null, null, null, now.plusDays(7))));
	}

	private static void verifyRetentionAndReferenceProtection(
		Connection connection, UUID userId, OffsetDateTime now) throws SQLException {
		IdempotencyRecord unexpired = succeededRecord(userId, "unexpired-cleanup-key", requestHash(20), now, now.plusDays(7));
		insertRecord(connection, unexpired);
		assertRejected("23514", () -> deleteRecord(connection, unexpired.id()));

		IdempotencyRecord processing = new IdempotencyRecord(
			UUID.randomUUID(), userId, null, null, "postTransaction", "processing-cleanup-key", requestHash(21),
			"PROCESSING", null, null, null, null,
			now, null, now, now.plusSeconds(30), null, now.plusDays(7));
		insertRecord(connection, processing);
		assertRejected("23514", () -> deleteRecord(connection, processing.id()));

		OffsetDateTime expiredCreatedAt = now.minusDays(8);
		IdempotencyRecord transactionReferenced = succeededRecord(
			userId, "transaction-reference-key", requestHash(22), expiredCreatedAt, now.minusDays(1));
		insertRecord(connection, transactionReferenced);
		insertTransactionReference(connection, userId, transactionReferenced.id(), now.minusDays(7));
		assertRejected("23503", () -> deleteRecord(connection, transactionReferenced.id()));

		IdempotencyRecord syncReferenced = succeededRecord(
			userId, "sync-reference-key", requestHash(23), expiredCreatedAt, now.minusDays(1));
		insertRecord(connection, syncReferenced);
		insertSyncReference(connection, userId, syncReferenced.id(), now.minusDays(7));
		assertRejected("23503", () -> deleteRecord(connection, syncReferenced.id()));
		// 两个业务表均用外键阻断悬空幂等记录，而不是只依赖清理触发器。
		assertRejected("23503", () -> insertTransactionReference(connection, userId, UUID.randomUUID(), now));
		assertRejected("23503", () -> insertSyncReference(connection, userId, UUID.randomUUID(), now));

		IdempotencyRecord eligible = succeededRecord(
			userId, "reusable-after-cleanup-key", requestHash(24), expiredCreatedAt, now.minusDays(1));
		insertRecord(connection, eligible);
		deleteRecord(connection, eligible.id());
		assertEquals(0, count(connection, "SELECT COUNT(*) FROM idempotency_records WHERE id = ?", eligible.id()));
		// 删除完成后，唯一作用域释放；相同 Key 是一次新请求而不是历史结果重放。
		insertRecord(connection, succeededRecord(
			userId, "reusable-after-cleanup-key", requestHash(25), now, now.plusDays(7)));
	}

	private static IdempotencyRecord succeededRecord(
		UUID userId, String key, String requestHash, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
		return new IdempotencyRecord(UUID.randomUUID(), userId, null, null, "postTransaction", key, requestHash,
			"SUCCEEDED", 200, emptyReference(), null, null,
			createdAt, createdAt.plusSeconds(1), null, null, null, expiresAt);
	}

	private static void insertUser(Connection connection, UUID userId, OffsetDateTime now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '幂等测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""")) {
			statement.setObject(1, userId);
			statement.setString(2, "idempotency-baseline@example.test");
			statement.setString(3, "idempotency-baseline@example.test");
			statement.setString(4, now.toString());
			statement.setString(5, "$argon2id$test");
			statement.setString(6, now.toString());
			statement.setString(7, now.toString());
			statement.executeUpdate();
		}
	}

	private static void insertLegacyRecord(
		Connection connection, UUID id, UUID userId, OffsetDateTime createdAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO idempotency_records (
				id, user_id, api_major_version, operation_id, idempotency_key, request_hash, status,
				response_status, response_reference, resource_type, resource_id, created_at, completed_at, expires_at
			) VALUES (?, ?, 1, 'registerUser', 'legacy-idempotency-key', ?, 'SUCCEEDED', 201,
				CAST('{"legacy":"preserved"}' AS jsonb), NULL, NULL, CAST(? AS timestamptz),
				CAST(? AS timestamptz), CAST(? AS timestamptz))
			""")) {
			statement.setObject(1, id);
			statement.setObject(2, userId);
			statement.setString(3, requestHash(99));
			statement.setString(4, createdAt.toString());
			statement.setString(5, createdAt.plusSeconds(1).toString());
			statement.setString(6, createdAt.plusDays(1).toString());
			statement.executeUpdate();
		}
	}

	private static void insertRecord(Connection connection, IdempotencyRecord record) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO idempotency_records (
				id, user_id, anonymous_subject_hash, anonymous_subject_hash_key_version, api_major_version,
				operation_id, idempotency_key, request_hash, status, response_status, response_reference,
				resource_type, resource_id, created_at, completed_at, processing_started_at,
				processing_lease_expires_at, retry_after_at, expires_at
			) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?,
				CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz),
				CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
			""")) {
			statement.setObject(1, record.id());
			statement.setObject(2, record.userId());
			statement.setBytes(3, record.anonymousSubjectHash());
			statement.setObject(4, record.anonymousSubjectHashKeyVersion());
			statement.setString(5, record.operationId());
			statement.setString(6, record.idempotencyKey());
			statement.setString(7, record.requestHash());
			statement.setString(8, record.status());
			statement.setObject(9, record.responseStatus());
			statement.setString(10, record.responseReference());
			statement.setString(11, record.resourceType());
			statement.setObject(12, record.resourceId());
			setTimestamp(statement, 13, record.createdAt());
			setTimestamp(statement, 14, record.completedAt());
			setTimestamp(statement, 15, record.processingStartedAt());
			setTimestamp(statement, 16, record.processingLeaseExpiresAt());
			setTimestamp(statement, 17, record.retryAfterAt());
			setTimestamp(statement, 18, record.expiresAt());
			statement.executeUpdate();
		}
	}

	private static void insertTransactionReference(
		Connection connection, UUID userId, UUID idempotencyRecordId, OffsetDateTime now) throws SQLException {
		boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			UUID transactionId = UUID.randomUUID();
			try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO transactions (
					id, transaction_type, status, business_at, business_date, timezone, source,
					idempotency_record_id, root_transaction_id, version_no, created_by, updated_by,
					created_at, updated_at
				) VALUES (?, 'EXPENSE', 'DRAFT', CAST(? AS timestamptz), ?, 'Asia/Shanghai', 'MANUAL',
					?, ?, 1, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz))
				""")) {
				statement.setObject(1, transactionId);
				statement.setString(2, now.toString());
				statement.setDate(3, Date.valueOf(now.toLocalDate()));
				statement.setObject(4, idempotencyRecordId);
				statement.setObject(5, transactionId);
				statement.setObject(6, userId);
				statement.setObject(7, userId);
				statement.setString(8, now.toString());
				statement.setString(9, now.toString());
				statement.executeUpdate();
			}
			connection.commit();
		} catch (SQLException exception) {
			connection.rollback();
			throw exception;
		} finally {
			connection.setAutoCommit(autoCommit);
		}
	}

	private static void insertSyncReference(
		Connection connection, UUID userId, UUID idempotencyRecordId, OffsetDateTime now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO sync_operations (
				id, user_id, device_id, idempotency_record_id, entity_type, entity_id,
				operation_type, status, processed_at
			) VALUES (?, ?, 'idempotency-test-device', ?, 'TRANSACTION', ?, 'CREATE', 'APPLIED',
				CAST(? AS timestamptz))
			""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, userId);
			statement.setObject(3, idempotencyRecordId);
			statement.setObject(4, UUID.randomUUID());
			statement.setString(5, now.toString());
			statement.executeUpdate();
		}
	}

	private static void deleteRecord(Connection connection, UUID id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM idempotency_records WHERE id = ?")) {
			statement.setObject(1, id);
			statement.executeUpdate();
		}
	}

	private static void migrateTo(org.testcontainers.postgresql.PostgreSQLContainer database, String target) {
		Flyway.configure()
			.dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
			.locations("classpath:db/migration")
			.target(target)
			.load()
			.migrate();
	}

	private static Connection connection(org.testcontainers.postgresql.PostgreSQLContainer database) throws SQLException {
		return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
	}

	private static void setTimestamp(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
		statement.setString(index, value == null ? null : value.toString());
	}

	private static int count(Connection connection, String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setObject(index + 1, parameters[index]);
			}
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}

	private static String stringValue(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getString(1);
		}
	}

	private static boolean booleanValue(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getBoolean(1);
		}
	}

	private static void assertRejected(SqlAction action) {
		assertRejected("23514", action);
	}

	private static void assertRejected(String expectedSqlState, SqlAction action) {
		SQLException exception = assertThrows(SQLException.class, action::run);
		assertEquals(expectedSqlState, exception.getSQLState());
	}

	private static String emptyReference() {
		return "{\"kind\":\"EMPTY\"}";
	}

	private static String resourceReference(UUID resourceId) {
		return "{\"kind\":\"RESOURCE\",\"location\":\"/api/v1/accounts/" + resourceId
			+ "\",\"etag\":\"\\\"1\\\"\",\"resourceVersion\":1}";
	}

	private static String problemReference(String errorCode) {
		return "{\"kind\":\"PROBLEM\",\"errorCode\":\"" + errorCode + "\"}";
	}

	private static String retryableProblemReference() {
		return "{\"kind\":\"PROBLEM\",\"errorCode\":\"TEMPORARILY_UNAVAILABLE\",\"retryAfterSeconds\":5}";
	}

	private static String oversizedResourceReference() {
		return "{\"kind\":\"RESOURCE\",\"location\":\"/" + "a".repeat(8_193) + "\"}";
	}

	private static String unsafeResponseReference() {
		return "{\"kind\":\"EMPTY\",\"credential\":\"test-only\"}";
	}

	private static String requestHash(int value) {
		return String.format("%064x", value);
	}

	private static byte[] hash(byte value) {
		byte[] hash = new byte[32];
		hash[0] = value;
		return hash;
	}

	private record IdempotencyRecord(
		UUID id,
		UUID userId,
		byte[] anonymousSubjectHash,
		Integer anonymousSubjectHashKeyVersion,
		String operationId,
		String idempotencyKey,
		String requestHash,
		String status,
		Integer responseStatus,
		String responseReference,
		String resourceType,
		UUID resourceId,
		OffsetDateTime createdAt,
		OffsetDateTime completedAt,
		OffsetDateTime processingStartedAt,
		OffsetDateTime processingLeaseExpiresAt,
		OffsetDateTime retryAfterAt,
		OffsetDateTime expiresAt) {
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}
}
