package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import app.ziji.shared.application.TransactionRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V013 验收：PositiveMoney 的 22 位整数上限必须能无损落入 LiquidityHold 事实与可用余额投影。 */
@SpringBootTest
@ActiveProfiles("test")
class LiquidityHoldMoneyCapacityPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
	private static final BigDecimal MAX_API_AMOUNT = new BigDecimal("9999999999999999999999.99");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactions;

	@Test
	void V013WidenedLiquidityHoldAndAvailabilityColumnsAcceptPositiveMoneyUpperBound() {
		assertEquals("30/8", numericShape("liquidity_holds", "amount"));
		assertEquals("30/8", numericShape("account_liquidity_snapshots", "ledger_balance"));
		assertEquals("30/8", numericShape("account_liquidity_snapshots", "unavailable_amount"));
		assertEquals("30/8", numericShape("account_liquidity_snapshots", "available_balance"));

		UUID userId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID holdId = UUID.randomUUID();
		insertUser(userId);
		insertAccount(accountId, userId);

		int changed = jdbc.update("""
			INSERT INTO liquidity_holds (
				id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
				updated_at, version
			) VALUES (?, ?, 'FROZEN', ?, 'CNY', ?, NULL, NULL, 'MANUAL', '容量边界',
				?, NULL, 1, NULL, NULL, ?, ?, ?, 1)
			""", holdId, accountId, MAX_API_AMOUNT, timestamp(), holdId, userId, timestamp(), timestamp());

		assertEquals(1, changed);
		BigDecimal persisted = jdbc.queryForObject(
			"SELECT amount FROM liquidity_holds WHERE id = ?", BigDecimal.class, holdId);
		assertTrue(persisted.compareTo(MAX_API_AMOUNT) == 0);

		int snapshotChanged = jdbc.update("""
			INSERT INTO account_liquidity_snapshots (
				account_id, business_date, ledger_balance, unavailable_amount, available_balance, currency,
				as_of_change_sequence, calculated_at
			) VALUES (?, ?, ?, ?, 0, 'CNY', 0, ?)
			""", accountId, LocalDate.of(2026, 8, 16), MAX_API_AMOUNT, MAX_API_AMOUNT, timestamp());
		assertEquals(1, snapshotChanged);
		assertTrue(jdbc.queryForObject(
			"SELECT ledger_balance FROM account_liquidity_snapshots WHERE account_id = ?", BigDecimal.class, accountId)
			.compareTo(MAX_API_AMOUNT) == 0);
		assertTrue(jdbc.queryForObject(
			"SELECT unavailable_amount FROM account_liquidity_snapshots WHERE account_id = ?", BigDecimal.class, accountId)
			.compareTo(MAX_API_AMOUNT) == 0);
		assertTrue(jdbc.queryForObject(
			"SELECT available_balance FROM account_liquidity_snapshots WHERE account_id = ?", BigDecimal.class, accountId)
			.compareTo(BigDecimal.ZERO) == 0);
	}

	private String numericShape(String table, String column) {
		return jdbc.queryForObject("""
			SELECT numeric_precision || '/' || numeric_scale
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
			""", String.class, table, column);
	}

	private void insertUser(UUID userId) {
		jdbc.update("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, ?, 'test-only-hash', 1, '容量测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(), timestamp(), timestamp());
	}

	private void insertAccount(UUID accountId, UUID userId) {
		UUID membershipId = UUID.randomUUID();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (
					id, account_class, account_type, name, institution, currency, note, status, archived_at,
					created_by, created_at, updated_at, version
				) VALUES (?, 'ASSET', 'BANK', '容量账户', NULL, 'CNY', NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, userId, timestamp(), timestamp());
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, userId, timestamp());
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, timestamp(), userId, timestamp());
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, timestamp());
		});
	}

	private static Timestamp timestamp() {
		return Timestamp.from(NOW);
	}
}
