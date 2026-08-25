package app.ziji;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/** QA-LED-003：真实 PostgreSQL 验证普通应用角色不能修改或删除已入账账务事实。 */
@SpringBootTest
@ActiveProfiles("test")
class PostedLedgerFactsImmutabilityPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-25T03:04:05Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 25);
	private static final String APP_ROLE = "ziji_app";
	private static final String OWNER_ROLE = "ziji";
	private static final String POSTED_TRANSACTION_UPDATE_STATE = "55000";
	private static final String INSUFFICIENT_PRIVILEGE_STATE = "42501";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void appRoleCannotMutatePostedOriginalOrReversalFactsAndEveryFailurePreservesSnapshot() throws SQLException {
		Fixture fixture = fixture();
		assertPrivilegeMatrix();

		assertRejectedAndUnchanged(
			fixture,
			"original transaction UPDATE",
			POSTED_TRANSACTION_UPDATE_STATE,
			"UPDATE transactions SET note = ? WHERE id = ?",
			"普通角色禁止修改原交易",
			fixture.originalTransactionId());
		assertRejectedAndUnchanged(
			fixture,
			"reversal transaction UPDATE",
			POSTED_TRANSACTION_UPDATE_STATE,
			"UPDATE transactions SET note = ? WHERE id = ?",
			"普通角色禁止修改冲正交易",
			fixture.reversalTransactionId());

		assertRejectedAndUnchanged(
			fixture,
			"original transaction DELETE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"DELETE FROM transactions WHERE id = ?",
			fixture.originalTransactionId());
		assertRejectedAndUnchanged(
			fixture,
			"reversal transaction DELETE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"DELETE FROM transactions WHERE id = ?",
			fixture.reversalTransactionId());

		assertRejectedAndUnchanged(
			fixture,
			"original ledger entry UPDATE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"UPDATE ledger_entries SET amount = ? WHERE transaction_id = ? AND sequence_no = 1",
			new java.math.BigDecimal("101.00"),
			fixture.originalTransactionId());
		assertRejectedAndUnchanged(
			fixture,
			"reversal ledger entry UPDATE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"UPDATE ledger_entries SET amount = ? WHERE transaction_id = ? AND sequence_no = 1",
			new java.math.BigDecimal("101.00"),
			fixture.reversalTransactionId());

		assertRejectedAndUnchanged(
			fixture,
			"original ledger entry DELETE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"DELETE FROM ledger_entries WHERE transaction_id = ? AND sequence_no = 1",
			fixture.originalTransactionId());
		assertRejectedAndUnchanged(
			fixture,
			"reversal ledger entry DELETE",
			INSUFFICIENT_PRIVILEGE_STATE,
			"DELETE FROM ledger_entries WHERE transaction_id = ? AND sequence_no = 1",
			fixture.reversalTransactionId());
	}

	private void assertPrivilegeMatrix() {
		assertEquals(Boolean.TRUE, hasTablePrivilege("transactions", "UPDATE"));
		assertEquals(Boolean.FALSE, hasTablePrivilege("transactions", "DELETE"));
		assertEquals(Boolean.FALSE, hasTablePrivilege("ledger_entries", "UPDATE"));
		assertEquals(Boolean.FALSE, hasTablePrivilege("ledger_entries", "DELETE"));
	}

	private void assertRejectedAndUnchanged(
		Fixture fixture, String operation, String expectedSqlState, String sql, Object... arguments) throws SQLException {
		FactSnapshot before = snapshot(fixture);
		assertRejectedAsApp(operation, expectedSqlState, sql, arguments);
		FactSnapshot after = snapshot(fixture);

		assertEquals(before.transactionRowCount(), after.transactionRowCount(), operation + " transaction row count");
		assertEquals(before.entryRowCount(), after.entryRowCount(), operation + " ledger entry row count");
		assertEquals(before.transactions(), after.transactions(), operation + " transaction snapshot");
		assertEquals(before.ledgerEntries(), after.ledgerEntries(), operation + " ledger entry snapshot");
	}

	private void assertRejectedAsApp(
		String operation, String expectedSqlState, String sql, Object... arguments) throws SQLException {
		SQLException failure = null;
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				assertEquals(OWNER_ROLE, currentRole(connection, "current_user"), operation + " owner user");
				try (PreparedStatement statement = connection.prepareStatement("SET LOCAL ROLE " + APP_ROLE)) {
					statement.execute();
				}
				assertEquals(APP_ROLE, currentRole(connection, "current_role"), operation + " switched role");
				try (PreparedStatement statement = connection.prepareStatement(sql)) {
					bind(statement, arguments);
					statement.executeUpdate();
					fail(operation + " unexpectedly succeeded");
				} catch (SQLException exception) {
					failure = exception;
				}
			} finally {
				// 失败语句会使 PostgreSQL 事务进入 aborted 状态，必须 rollback 后才能复用连接并复位 SET LOCAL ROLE。
				connection.rollback();
			}

			assertNotNull(failure, operation + " did not return a PostgreSQL error");
			assertEquals(expectedSqlState, failure.getSQLState(), operation + " SQLSTATE");
			assertEquals(OWNER_ROLE, currentRole(connection, "current_role"), operation + " role after rollback");
			System.out.printf("QA-LED-003 %s rejected with SQLSTATE %s%n", operation, failure.getSQLState());
		}

		// 连接归还池后再次取得仍应是 owner，防止普通应用角色泄漏到后续业务连接。
		try (Connection connection = dataSource.getConnection()) {
			assertEquals(OWNER_ROLE, currentRole(connection, "current_user"), operation + " pooled user");
			assertEquals(OWNER_ROLE, currentRole(connection, "current_role"), operation + " pooled role");
		}
	}

	private String currentRole(Connection connection, String expression) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT " + expression);
			ResultSet resultSet = statement.executeQuery()) {
			resultSet.next();
			return resultSet.getString(1);
		}
	}

	private void bind(PreparedStatement statement, Object... arguments) throws SQLException {
		for (int index = 0; index < arguments.length; index++) {
			statement.setObject(index + 1, arguments[index]);
		}
	}

	private Boolean hasTablePrivilege(String table, String privilege) {
		return jdbc.queryForObject(
			"SELECT has_table_privilege('" + APP_ROLE + "', 'public." + table + "', ?)",
			Boolean.class,
			privilege);
	}

	private FactSnapshot snapshot(Fixture fixture) {
		List<Map<String, Object>> transactions = jdbc.queryForList(
			"SELECT * FROM transactions WHERE id IN (?, ?) ORDER BY id",
			fixture.originalTransactionId(), fixture.reversalTransactionId());
		List<Map<String, Object>> ledgerEntries = jdbc.queryForList(
			"SELECT * FROM ledger_entries WHERE transaction_id IN (?, ?) ORDER BY transaction_id, sequence_no",
			fixture.originalTransactionId(), fixture.reversalTransactionId());
		return new FactSnapshot(transactions, ledgerEntries, transactions.size(), ledgerEntries.size());
	}

	private Fixture fixture() {
		UUID userId = UUID.randomUUID();
		UUID debitLedgerId = UUID.randomUUID();
		UUID creditLedgerId = UUID.randomUUID();
		UUID originalTransactionId = UUID.randomUUID();
		UUID reversalTransactionId = UUID.randomUUID();
		Timestamp timestamp = Timestamp.from(NOW);
		Date businessDate = Date.valueOf(BUSINESS_DATE);

		insertUser(userId, timestamp);
		insertLedgerAccount(debitLedgerId, userId, "QA_LEDGER_DEBIT");
		insertLedgerAccount(creditLedgerId, userId, "QA_LEDGER_CREDIT");

		// owner 先写入 DRAFT 交易和两条同币种等额反向分录，再以 POSTED 状态完成真实约束校验。
		insertTransaction(originalTransactionId, userId, "ADJUSTMENT", "原始已入账测试交易", null, timestamp, businessDate);
		insertEntry(UUID.randomUUID(), originalTransactionId, debitLedgerId, 1, "D", timestamp, businessDate);
		insertEntry(UUID.randomUUID(), originalTransactionId, creditLedgerId, 2, "C", timestamp, businessDate);
		postTransaction(originalTransactionId, timestamp);

		// 该最小 fixture 只建立已入账冲正事实及其反向分录，不复制生产服务的冲正算法。
		insertTransaction(reversalTransactionId, userId, "REVERSAL", "原始交易冲正", originalTransactionId, timestamp, businessDate);
		insertEntry(UUID.randomUUID(), reversalTransactionId, debitLedgerId, 1, "C", timestamp, businessDate);
		insertEntry(UUID.randomUUID(), reversalTransactionId, creditLedgerId, 2, "D", timestamp, businessDate);
		postTransaction(reversalTransactionId, timestamp);

		return new Fixture(originalTransactionId, reversalTransactionId);
	}

	private void insertUser(UUID userId, Timestamp timestamp) {
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, 'QA-LED-003 测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""",
			userId,
			userId + "@example.test",
			userId + "@example.test",
			timestamp,
			timestamp,
			timestamp);
	}

	private void insertLedgerAccount(UUID ledgerAccountId, UUID userId, String code) {
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', 'ASSET', 'CNY', 'ACTIVE', ?)
			""", ledgerAccountId, userId, code + "_" + ledgerAccountId, Timestamp.from(NOW));
	}

	private void insertTransaction(
		UUID transactionId,
		UUID userId,
		String transactionType,
		String note,
		UUID reversalOfId,
		Timestamp timestamp,
		Date businessDate) {
		jdbc.update("""
			INSERT INTO transactions
				(id, transaction_type, status, business_at, business_date, timezone, note, source,
				 root_transaction_id, reversal_of_id, version_no, created_by, updated_by, created_at, updated_at)
			VALUES (?, ?, 'DRAFT', ?, ?, 'Asia/Shanghai', ?, 'ADJUSTMENT', ?, ?, 1, ?, ?, ?, ?)
			""",
			transactionId,
			transactionType,
			timestamp,
			businessDate,
			note,
			transactionId,
			reversalOfId,
			userId,
			userId,
			timestamp,
			timestamp);
	}

	private void insertEntry(
		UUID entryId,
		UUID transactionId,
		UUID ledgerAccountId,
		int sequenceNo,
		String direction,
		Timestamp timestamp,
		Date businessDate) {
		jdbc.update("""
			INSERT INTO ledger_entries
				(id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
			VALUES (?, ?, ?, ?, ?, 100.00, 'CNY', ?, ?)
			""", entryId, transactionId, ledgerAccountId, sequenceNo, direction, businessDate, timestamp);
	}

	private void postTransaction(UUID transactionId, Timestamp timestamp) {
		jdbc.update(
			"UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
			timestamp,
			timestamp,
			transactionId);
	}

	private record Fixture(UUID originalTransactionId, UUID reversalTransactionId) {
	}

	private record FactSnapshot(
		List<Map<String, Object>> transactions,
		List<Map<String, Object>> ledgerEntries,
		int transactionRowCount,
		int entryRowCount) {
	}
}
