package app.ziji;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL 验收：复用 V007 的精度触发器和提交时逐币种平衡约束。 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerPrecisionPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-15T04:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 15);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Test
	void v007RejectsLedgerEntryBeyondCurrencyMinorUnitsBeforePersistingFacts() {
		UUID transactionId = UUID.randomUUID();
		UUID userId = insertUser("ledger-precision");

		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> {
			UUID accountId = insertLedgerAccount(userId, "PRECISION_ACCOUNT", "CNY");
			insertDraftTransaction(transactionId, userId);
			insertEntry(transactionId, accountId, 1, "D", "10.001", "CNY");
		}));

		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
	}

	@Test
	void v007RejectsPostedTransactionUnbalancedPerCurrencyAtCommitAndRollsBackAllFacts() {
		UUID transactionId = UUID.randomUUID();
		UUID userId = insertUser("ledger-balance");

		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> {
			UUID debitAccountId = insertLedgerAccount(userId, "BALANCE_DEBIT", "CNY");
			UUID creditAccountId = insertLedgerAccount(userId, "BALANCE_CREDIT", "CNY");
			insertDraftTransaction(transactionId, userId);
			insertEntry(transactionId, debitAccountId, 1, "D", "10.00", "CNY");
			insertEntry(transactionId, creditAccountId, 2, "C", "9.99", "CNY");
			jdbc.update("""
				UPDATE transactions
				SET status = 'POSTED', posted_at = ?, updated_at = ?
				WHERE id = ?
				""", timestamp(), timestamp(), transactionId);
		}));

		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
	}

	@Test
	void v007DoesNotLetEqualCrossCurrencyTotalsBalanceAtCommit() {
		UUID transactionId = UUID.randomUUID();
		UUID userId = insertUser("ledger-cross-currency");

		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> {
			UUID cnyAccountId = insertLedgerAccount(userId, "CROSS_CURRENCY_CNY", "CNY");
			UUID usdAccountId = insertLedgerAccount(userId, "CROSS_CURRENCY_USD", "USD");
			insertDraftTransaction(transactionId, userId);
			insertEntry(transactionId, cnyAccountId, 1, "D", "10.00", "CNY");
			insertEntry(transactionId, usdAccountId, 2, "C", "10.00", "USD");
			jdbc.update("""
				UPDATE transactions
				SET status = 'POSTED', posted_at = ?, updated_at = ?
				WHERE id = ?
				""", timestamp(), timestamp(), transactionId);
		}));

		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '账务精度测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, suffix + "@example.test", suffix + "@example.test", timestamp(),
			timestamp(), timestamp());
		return userId;
	}

	private UUID insertLedgerAccount(UUID userId, String code, String currency) {
		UUID accountId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', 'ASSET', ?, 'ACTIVE', ?)
			""", accountId, userId, code, currency, timestamp());
		return accountId;
	}

	private void insertDraftTransaction(UUID transactionId, UUID userId) {
		jdbc.update("""
			INSERT INTO transactions
				(id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
			VALUES (?, 'ADJUSTMENT', 'DRAFT', ?, ?, 'Asia/Shanghai', 'ADJUSTMENT', ?, 1, ?, ?, ?, ?)
			""", transactionId, timestamp(), Date.valueOf(BUSINESS_DATE), transactionId,
			userId, userId, timestamp(), timestamp());
	}

	private void insertEntry(
		UUID transactionId,
		UUID ledgerAccountId,
		int sequenceNo,
		String direction,
		String amount,
		String currency) {
		jdbc.update("""
			INSERT INTO ledger_entries
				(id, transaction_id, ledger_account_id, sequence_no, direction, amount,
				 currency, business_date, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), transactionId, ledgerAccountId, sequenceNo, direction,
			new BigDecimal(amount), currency, Date.valueOf(BUSINESS_DATE), timestamp());
	}

	private int count(String sql, UUID id) {
		Integer value = jdbc.queryForObject(sql, Integer.class, id);
		return value == null ? 0 : value;
	}

	private Timestamp timestamp() {
		return Timestamp.from(NOW);
	}
}
