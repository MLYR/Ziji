package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V014/V015/V017 验收：每消费者 outbox 回执、内置 SYNC 订阅与历史升级基线。 */
@Testcontainers
class OutboxConsumerReceiptMigrationTests {

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer EMPTY_POSTGRES = newContainer(
		"ziji_outbox_receipt_empty");

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer UPGRADE_POSTGRES = newContainer(
		"ziji_outbox_receipt_upgrade");

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer V015_UPGRADE_POSTGRES = newContainer(
		"ziji_outbox_receipt_v015_upgrade");

	@Test
	void emptyDatabaseCreatesIndependentReceiptFactsAndConstraints() throws Exception {
		migrate(EMPTY_POSTGRES, null);
		migrate(EMPTY_POSTGRES, null);
		try (Connection connection = connection(EMPTY_POSTGRES)) {
			Instant syncSubscriptionStart = syncSubscriptionStart(connection, "TransactionPosted");
			assertEquals(17, count(connection, "SELECT COUNT(*) FROM flyway_schema_history"));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM outbox_consumer_subscriptions
				WHERE consumer_name = 'SYNC' AND aggregate_type = 'Transaction'
				  AND event_type = 'TransactionPosted' AND subscribed_until IS NULL
				  AND required_for_cleanup
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM outbox_consumer_subscriptions
				WHERE consumer_name = 'SYNC' AND aggregate_type = 'Transaction'
				  AND event_type = 'TransactionReversed' AND subscribed_until IS NULL
				  AND required_for_cleanup
				"""));
			assertEquals(syncSubscriptionStart, syncSubscriptionCreatedAt(connection, "TransactionPosted"));
			assertEquals(syncSubscriptionStart, syncSubscriptionStart(connection, "TransactionReversed"));
			assertEquals(syncSubscriptionStart, syncSubscriptionCreatedAt(connection, "TransactionReversed"));
			assertFalse(Instant.EPOCH.equals(syncSubscriptionStart));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name = 'outbox_consumer_receipts'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_constraint
				WHERE conrelid = 'outbox_consumer_receipts'::regclass
				  AND conname = 'pk_outbox_consumer_receipts'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_constraint
				WHERE conrelid = 'outbox_consumer_receipts'::regclass
				  AND conname = 'fk_outbox_consumer_receipts_event'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name = 'outbox_consumer_subscriptions'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_constraint
				WHERE conrelid = 'outbox_consumer_subscriptions'::regclass
				  AND conname = 'ex_outbox_consumer_subscriptions_period'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_views
				WHERE schemaname = 'public' AND viewname = 'outbox_event_cleanup_eligibility'
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_indexes
				WHERE schemaname = 'public' AND indexname = 'idx_outbox_consumer_receipts_ready'
				"""));
			assertEquals(3, count(connection, """
				SELECT COUNT(*) FROM pg_indexes
				WHERE schemaname = 'public'
				  AND indexname IN (
					'idx_outbox_consumer_receipts_ready',
					'idx_outbox_consumer_receipts_lease',
					'idx_outbox_consumer_receipts_event')
				"""));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_indexes
				WHERE schemaname = 'public' AND indexname = 'idx_outbox_consumer_subscriptions_required'
				"""));
			assertTrue(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_receipts', 'SELECT')"));
			assertTrue(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_receipts', 'INSERT')"));
			assertTrue(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_receipts', 'UPDATE')"));
			assertFalse(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_receipts', 'DELETE')"));
			assertTrue(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_subscriptions', 'SELECT')"));
			assertFalse(booleanValue(connection,
				"SELECT has_table_privilege('ziji_app', 'public.outbox_consumer_subscriptions', 'INSERT')"));
			assertEquals(0, count(connection, """
				SELECT COUNT(*) FROM information_schema.role_table_grants
				WHERE table_schema = 'public'
				  AND table_name IN ('outbox_consumer_receipts', 'outbox_consumer_subscriptions')
				  AND grantee = 'PUBLIC'
				"""));

			UUID beforeSubscriptionEventId = UUID.randomUUID();
			UUID subscribedEventId = UUID.randomUUID();
			Instant subscribedEventTime = syncSubscriptionStart.plusSeconds(10);
			insertOutboxEvent(connection, beforeSubscriptionEventId, syncSubscriptionStart.minusMillis(1));
			insertOutboxEvent(connection, subscribedEventId, subscribedEventTime);
			insertSubscription(connection, "BALANCE", "Transaction", "TransactionPosted",
				syncSubscriptionStart.plusSeconds(5), null, true);
			insertSubscription(connection, "STATISTICS", "Transaction", "TransactionPosted",
				syncSubscriptionStart.plusSeconds(5), syncSubscriptionStart.plusSeconds(20), true);
			insertSubscription(connection, "EMAIL", "Transaction", "TransactionPosted",
				syncSubscriptionStart.minusSeconds(10), null, false);
			assertCleanupEligibility(connection, beforeSubscriptionEventId, 0, 0, 0, true);
			assertCleanupEligibility(connection, subscribedEventId, 3, 0, 3, false);
			assertThrows(SQLException.class, () -> insertSubscription(connection, "STATISTICS", "Transaction",
				"TransactionPosted", syncSubscriptionStart.plusSeconds(7), syncSubscriptionStart.plusSeconds(25), true));

			insertReceipt(connection, "SYNC", subscribedEventId, "PENDING", 0, null, null, null, null, null);
			insertReceipt(connection, "STATISTICS", subscribedEventId, "PROCESSING", 1, UUID.randomUUID(),
				subscribedEventTime.plusSeconds(30), null, null, null);
			insertReceipt(connection, "POSITION", subscribedEventId, "SUCCEEDED", 2, null, null,
				subscribedEventTime.plusSeconds(1), null, null);
			insertReceipt(connection, "EMAIL", subscribedEventId, "FAILED_RETRYABLE", 3, null, null,
				null, subscribedEventTime.plusSeconds(2), "EMAIL_TIMEOUT");
			insertReceipt(connection, "AUDIT", subscribedEventId, "FAILED_FINAL", 1, null, null,
				null, subscribedEventTime.plusSeconds(3), "PAYLOAD_INVALID");
			assertEquals(5, count(connection,
				"SELECT COUNT(*) FROM outbox_consumer_receipts WHERE outbox_event_id = '" + subscribedEventId + "'"));
			assertCleanupEligibility(connection, subscribedEventId, 3, 0, 3, false);
			insertReceipt(connection, "BALANCE", subscribedEventId, "SUCCEEDED", 1, null, null,
				subscribedEventTime.plusSeconds(4), null, null);
			markReceiptFinal(connection, "SYNC", subscribedEventId, subscribedEventTime.plusSeconds(5));
			markReceiptFinal(connection, "STATISTICS", subscribedEventId, subscribedEventTime.plusSeconds(5));
			assertEquals(6, count(connection,
				"SELECT COUNT(*) FROM outbox_consumer_receipts WHERE outbox_event_id = '" + subscribedEventId + "'"));
			assertCleanupEligibility(connection, subscribedEventId, 3, 3, 0, true);

			assertThrows(SQLException.class, () -> insertReceipt(
				connection, "BALANCE", subscribedEventId, "PENDING", 0, null, null, null, null, null));
			assertThrows(SQLException.class, () -> insertReceipt(
				connection, "NEW", subscribedEventId, "PENDING", -1, null, null, null, null, null));
			assertThrows(SQLException.class, () -> insertReceipt(
				connection, "NEW", subscribedEventId, "PROCESSING", 1, null, null, null, null, null));
			assertThrows(SQLException.class, () -> insertReceipt(
				connection, "NEW", subscribedEventId, "SUCCEEDED", 1, UUID.randomUUID(), subscribedEventTime.plusSeconds(30), null, null, null));

			assertThrows(SQLException.class, () -> deleteOutboxEvent(connection, subscribedEventId));
		}
	}

	@Test
	void v015DatabaseUpgradesBuiltInSyncBoundaryWithoutRewritingV015() throws Exception {
		migrate(V015_UPGRADE_POSTGRES, "15");
		try (Connection connection = connection(V015_UPGRADE_POSTGRES)) {
			assertEquals(-920552827, checksum(connection, "015"));
			assertEquals(Instant.EPOCH, syncSubscriptionStart(connection, "TransactionPosted"));
			assertEquals(Instant.EPOCH, syncSubscriptionCreatedAt(connection, "TransactionPosted"));
		}

		migrate(V015_UPGRADE_POSTGRES, null);

		try (Connection connection = connection(V015_UPGRADE_POSTGRES)) {
			assertEquals(17, count(connection, "SELECT COUNT(*) FROM flyway_schema_history"));
			assertEquals(-920552827, checksum(connection, "015"));
			Instant subscriptionStart = syncSubscriptionStart(connection, "TransactionPosted");
			assertFalse(Instant.EPOCH.equals(subscriptionStart));
			assertEquals(subscriptionStart, syncSubscriptionCreatedAt(connection, "TransactionPosted"));
			assertEquals(subscriptionStart, syncSubscriptionStart(connection, "TransactionReversed"));
			assertEquals(subscriptionStart, syncSubscriptionCreatedAt(connection, "TransactionReversed"));
		}
	}

	@Test
	void v013DatabaseUpgradesToCurrentWithoutRewritingPreviousMigrations() throws Exception {
		migrate(UPGRADE_POSTGRES, "13");
		Map<String, Integer> previousChecksums;
		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			previousChecksums = checksumsThroughV013(connection);
		}

		migrate(UPGRADE_POSTGRES, null);

		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			assertEquals(17, count(connection, "SELECT COUNT(*) FROM flyway_schema_history"));
			assertEquals(previousChecksums, checksumsThroughV013(connection));
			assertFalse(Instant.EPOCH.equals(syncSubscriptionStart(connection, "TransactionPosted")));
			assertEquals(1, count(connection, """
				SELECT COUNT(*) FROM pg_constraint
				WHERE conrelid = 'outbox_consumer_receipts'::regclass
				  AND conname = 'ck_outbox_consumer_receipts_lifecycle'
				"""));
		}
	}

	private static org.testcontainers.postgresql.PostgreSQLContainer newContainer(String databaseName) {
		return new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName(databaseName)
			.withUsername("ziji")
			.withPassword("ziji-test");
	}

	private static void migrate(org.testcontainers.postgresql.PostgreSQLContainer postgres, String target) {
		var configuration = Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private static Connection connection(org.testcontainers.postgresql.PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private static void insertOutboxEvent(Connection connection, UUID eventId, Instant occurredAt) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO outbox_events (
				id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			VALUES (?, 'Transaction', ?, 'TransactionPosted', CAST(? AS jsonb), 1, ?, NULL, 0, ?)
			""")) {
			statement.setObject(1, eventId);
			statement.setObject(2, UUID.randomUUID());
			statement.setString(3, "{}");
			statement.setTimestamp(4, Timestamp.from(occurredAt));
			statement.setTimestamp(5, Timestamp.from(occurredAt));
			statement.executeUpdate();
		}
	}

	private static void insertSubscription(
		Connection connection,
		String consumerName,
		String aggregateType,
		String eventType,
		Instant subscribedFrom,
		Instant subscribedUntil,
		boolean requiredForCleanup) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO outbox_consumer_subscriptions (
				consumer_name, aggregate_type, event_type, subscribed_from, subscribed_until,
				required_for_cleanup, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""")) {
			statement.setString(1, consumerName);
			statement.setString(2, aggregateType);
			statement.setString(3, eventType);
			statement.setTimestamp(4, Timestamp.from(subscribedFrom));
			statement.setObject(5, subscribedUntil == null ? null : Timestamp.from(subscribedUntil));
			statement.setBoolean(6, requiredForCleanup);
			statement.setTimestamp(7, Timestamp.from(Instant.now()));
			statement.executeUpdate();
		}
	}

	private static void markReceiptFinal(Connection connection, String consumerName, UUID eventId, Instant failedAt)
		throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE outbox_consumer_receipts
			SET status = 'FAILED_FINAL', claim_token = NULL, lease_expires_at = NULL,
				completed_at = NULL, failed_at = ?, error_code = 'PAYLOAD_INVALID', updated_at = ?
			WHERE consumer_name = ? AND outbox_event_id = ?
			""")) {
			statement.setTimestamp(1, Timestamp.from(failedAt));
			statement.setTimestamp(2, Timestamp.from(failedAt));
			statement.setString(3, consumerName);
			statement.setObject(4, eventId);
			assertEquals(1, statement.executeUpdate());
		}
	}

	private static void assertCleanupEligibility(
		Connection connection,
		UUID eventId,
		int requiredSubscriptions,
		int terminalReceipts,
		int missingReceipts,
		boolean eligible) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT required_subscription_count, terminal_receipt_count,
				missing_required_receipt_count, eligible_for_cleanup
			FROM outbox_event_cleanup_eligibility
			WHERE outbox_event_id = ?
			""")) {
			statement.setObject(1, eventId);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals(requiredSubscriptions, result.getInt(1));
				assertEquals(terminalReceipts, result.getInt(2));
				assertEquals(missingReceipts, result.getInt(3));
				assertEquals(eligible, result.getBoolean(4));
			}
		}
	}

	private static boolean booleanValue(Connection connection, String sql) throws SQLException {
		try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
			result.next();
			return result.getBoolean(1);
		}
	}

	private static void insertReceipt(
		Connection connection,
		String consumerName,
		UUID eventId,
		String status,
		int attemptCount,
		UUID claimToken,
		Instant leaseExpiresAt,
		Instant completedAt,
		Instant failedAt,
		String errorCode) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO outbox_consumer_receipts (
				consumer_name, outbox_event_id, status, claim_token, lease_expires_at,
				attempt_count, next_attempt_at, completed_at, failed_at, error_code,
				created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""")) {
			statement.setString(1, consumerName);
			statement.setObject(2, eventId);
			statement.setString(3, status);
			statement.setObject(4, claimToken);
			statement.setObject(5, leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt));
			statement.setInt(6, attemptCount);
			Instant receiptTime = Instant.now();
			statement.setTimestamp(7, Timestamp.from(receiptTime));
			statement.setObject(8, completedAt == null ? null : Timestamp.from(completedAt));
			statement.setObject(9, failedAt == null ? null : Timestamp.from(failedAt));
			statement.setString(10, errorCode);
			statement.setTimestamp(11, Timestamp.from(receiptTime));
			statement.setTimestamp(12, Timestamp.from(receiptTime));
			statement.executeUpdate();
		}
	}

	private static void deleteOutboxEvent(Connection connection, UUID eventId) throws SQLException {
		try (var statement = connection.prepareStatement("DELETE FROM outbox_events WHERE id = ?")) {
			statement.setObject(1, eventId);
			statement.executeUpdate();
		}
	}

	private static int checksum(Connection connection, String version) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT checksum FROM flyway_schema_history WHERE version = ?")) {
			statement.setString(1, version);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getInt(1);
			}
		}
	}

	private static Map<String, Integer> checksumsThroughV013(Connection connection) throws SQLException {
		Map<String, Integer> checksums = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT version, checksum
			FROM flyway_schema_history
			WHERE installed_rank <= 13
			ORDER BY installed_rank
			"""); ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				checksums.put(result.getString("version"), result.getInt("checksum"));
			}
		}
		return checksums;
	}

	private static Instant syncSubscriptionStart(Connection connection, String eventType) throws SQLException {
		return syncSubscriptionTimestamp(connection, eventType, "subscribed_from");
	}

	private static Instant syncSubscriptionCreatedAt(Connection connection, String eventType) throws SQLException {
		return syncSubscriptionTimestamp(connection, eventType, "created_at");
	}

	private static Instant syncSubscriptionTimestamp(Connection connection, String eventType, String column) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT %s
			FROM outbox_consumer_subscriptions
			WHERE consumer_name = 'SYNC' AND aggregate_type = 'Transaction' AND event_type = ?
			""".formatted(column))) {
			statement.setString(1, eventType);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getTimestamp(1).toInstant();
			}
		}
	}

	private static int count(Connection connection, String sql) throws SQLException {
		try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
			result.next();
			return result.getInt(1);
		}
	}
}
