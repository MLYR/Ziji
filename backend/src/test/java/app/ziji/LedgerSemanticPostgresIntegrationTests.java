package app.ziji;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.ledger.application.BalanceAdjustmentCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.application.NoTransactionDetails;
import app.ziji.ledger.application.PostedTransactionWrite;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntrySpec;
import app.ziji.ledger.domain.LedgerTransactionFactory;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionType;
import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.IncomeCommand;
import app.ziji.ledger.application.RefundCommand;
import app.ziji.ledger.application.TransferCommand;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL/Testcontainers 验收：语义命令事实写入复用 V007 延迟约束并保持原子性。 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerSemanticPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-15T04:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 15);
	private static final UUID INCOME_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000101");
	private static final UUID EXPENSE_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000201");

	@Autowired
	private LedgerCommandApplicationService service;

	@Autowired
	private LedgerTransactionStore ledgerTransactions;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void incomeAndExpensePersistExpectedDirectionsAndCategory() {
		Fixture fixture = fixture();
		Transaction income = service.postIncome(new IncomeCommand(
			fixture.userId, fixture.assetAccountId, fixture.incomeLedgerId, INCOME_CATEGORY_ID,
			money("8000.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "发薪方", "工资"));
		Transaction expense = service.postExpense(new ExpenseCommand(
			fixture.userId, fixture.assetAccountId, fixture.expenseLedgerId, EXPENSE_CATEGORY_ID,
			money("50.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "餐厅", "餐饮"));

		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND status = 'POSTED'", income.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND status = 'POSTED'", expense.transactionId()));
		assertEntry(income.transactionId(), 1, fixture.assetLedgerId, "D", "8000.00");
		assertEntry(income.transactionId(), 2, fixture.incomeLedgerId, "C", "8000.00");
		assertEntry(expense.transactionId(), 1, fixture.expenseLedgerId, "D", "50.00");
		assertEntry(expense.transactionId(), 2, fixture.assetLedgerId, "C", "50.00");
		assertEquals(2, count("""
			SELECT count(*) FROM transaction_categories
			WHERE transaction_id IN (?, ?) AND role = 'PRIMARY'
			""", income.transactionId(), expense.transactionId()));
	}

	@Test
	void refundInheritsOriginalExpenseAndRejectsOverRefund() {
		Fixture fixture = fixture();
		Transaction original = service.postExpense(new ExpenseCommand(
			fixture.userId, fixture.assetAccountId, fixture.expenseLedgerId, EXPENSE_CATEGORY_ID,
			money("50.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "餐厅", "原支出"));

		Transaction refund = service.postRefund(new RefundCommand(
			fixture.userId, fixture.assetAccountId, original.transactionId(), money("20.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "退款"));
		assertEntry(refund.transactionId(), 1, fixture.assetLedgerId, "D", "20.00");
		assertEntry(refund.transactionId(), 2, fixture.expenseLedgerId, "C", "20.00");
		assertEquals(1, count("""
			SELECT count(*) FROM refund_details
			WHERE transaction_id = ? AND original_transaction_id = ? AND category_id = ?
			""", refund.transactionId(), original.transactionId(), EXPENSE_CATEGORY_ID));

		assertThrows(LedgerCommandValidationException.class, () -> service.postRefund(new RefundCommand(
			fixture.userId, fixture.assetAccountId, original.transactionId(), money("30.01", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "超额退款")));
		assertEquals(1, count("SELECT count(*) FROM refund_details WHERE original_transaction_id = ?",
			original.transactionId()));
	}

	@Test
	void refundWithMissingOriginalReturnsValidationErrorWithoutFactWrite() {
		Fixture fixture = fixture();
		int before = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);

		assertThrows(LedgerCommandValidationException.class, () -> service.postRefund(new RefundCommand(
			fixture.userId, fixture.assetAccountId, UUID.randomUUID(), money("20.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "找不到原支出")));

		assertEquals(before, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
	}

	@Test
	void sameCurrencyTransferWithFeeHasNoIncomeTransactionAndFeeIsSeparate() {
		Fixture fixture = fixture();
		Transaction transfer = service.postTransfer(new TransferCommand(
			fixture.userId, fixture.assetAccountId, fixture.secondAssetAccountId,
			fixture.expenseLedgerId, EXPENSE_CATEGORY_ID, money("1000.00", CurrencyCode.CNY),
			money("2.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "转账"));

		assertEquals("TRANSFER", jdbc.queryForObject(
			"SELECT transaction_type FROM transactions WHERE id = ?", String.class, transfer.transactionId()));
		assertEquals(4, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transfer.transactionId()));
		assertEntry(transfer.transactionId(), 1, fixture.secondAssetLedgerId, "D", "1000.00");
		assertEntry(transfer.transactionId(), 2, fixture.assetLedgerId, "C", "1000.00");
		assertEntry(transfer.transactionId(), 3, fixture.expenseLedgerId, "D", "2.00");
		assertEntry(transfer.transactionId(), 4, fixture.assetLedgerId, "C", "2.00");
		Map<String, Object> details = jdbc.queryForMap(
			"SELECT from_amount, to_amount, fee_amount FROM transfer_details WHERE transaction_id = ?",
			transfer.transactionId());
		assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) details.get("from_amount")));
		assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) details.get("to_amount")));
		assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) details.get("fee_amount")));
		assertEquals("TRANSFER", jdbc.queryForObject(
			"SELECT transaction_type FROM transactions WHERE id = ?", String.class, transfer.transactionId()));
	}

	@Test
	void crossCurrencyTransferIsRejectedBeforeFacts() {
		Fixture fixture = fixture();
		UUID hkdAccountId = UUID.randomUUID();
		UUID hkdLedgerId = UUID.randomUUID();
		transactionRunner.required(() ->
			insertVisibleAccountWithPrimary(fixture.userId, hkdAccountId, hkdLedgerId, "HKD", "CASH"));

		int before = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);
		assertThrows(LedgerCommandValidationException.class, () -> service.postTransfer(new TransferCommand(
			fixture.userId, fixture.assetAccountId, hkdAccountId, null, null,
			money("100.00", CurrencyCode.CNY), null, NOW, BUSINESS_DATE, "Asia/Shanghai", null)));
		assertEquals(before, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE ledger_account_id = ?", hkdLedgerId));
	}

	@Test
	void balanceAdjustmentStoresComputedDifferenceAndSupportsDownwardCorrection() {
		Fixture fixture = fixture();
		Transaction increase = service.postBalanceAdjustment(new BalanceAdjustmentCommand(
			fixture.userId, fixture.assetAccountId, fixture.equityLedgerId, money("30.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "盘点上调"));
		assertEntry(increase.transactionId(), 1, fixture.assetLedgerId, "D", "30.00");
		assertEntry(increase.transactionId(), 2, fixture.equityLedgerId, "C", "30.00");
		assertAdjustment(increase.transactionId(), "0.00", "30.00", "30.00", "盘点上调");

		Transaction decrease = service.postBalanceAdjustment(new BalanceAdjustmentCommand(
			fixture.userId, fixture.assetAccountId, fixture.equityLedgerId, money("20.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "盘点下调"));
		assertEntry(decrease.transactionId(), 1, fixture.assetLedgerId, "C", "10.00");
		assertEntry(decrease.transactionId(), 2, fixture.equityLedgerId, "D", "10.00");
		assertAdjustment(decrease.transactionId(), "30.00", "20.00", "-10.00", "盘点下调");
	}

	@Test
	void failedCategoryWriteRollsBackTransactionAndEntriesTogether() {
		Fixture fixture = fixture();
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = new LedgerTransactionFactory(new PostingService()).createPosted(
			transactionId,
			TransactionType.EXPENSE,
			TransactionSource.MANUAL,
			NOW,
			BUSINESS_DATE,
			"Asia/Shanghai",
			NOW,
			List.of(
				new LedgerEntrySpec(fixture.expenseLedgerId, LedgerDirection.DEBIT, money("1.00", CurrencyCode.CNY)),
				new LedgerEntrySpec(fixture.assetLedgerId, LedgerDirection.CREDIT, money("1.00", CurrencyCode.CNY))));

		assertThrows(LedgerPersistenceException.class, () -> transactionRunner.required(() ->
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, fixture.userId, null, "测试", null, UUID.randomUUID(),
				new NoTransactionDetails()))));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
	}

	private Fixture fixture() {
		Fixture fixture = new Fixture(UUID.randomUUID());
		transactionRunner.required(() -> {
			insertUser(fixture.userId);
			insertVisibleAccountWithPrimary(fixture.userId, fixture.assetAccountId, fixture.assetLedgerId, "CNY", "BANK");
			insertVisibleAccountWithPrimary(fixture.userId, fixture.secondAssetAccountId,
				fixture.secondAssetLedgerId, "CNY", "ALIPAY");
			insertSystemLedger(fixture.userId, fixture.incomeLedgerId, "INCOME_WAGE", "INCOME", "CNY");
			insertSystemLedger(fixture.userId, fixture.expenseLedgerId, "EXPENSE_FOOD", "EXPENSE", "CNY");
			insertSystemLedger(fixture.userId, fixture.equityLedgerId,
				"EQUITY_BALANCE_ADJUSTMENT", "EQUITY", "CNY");
		});
		return fixture;
	}

	private void insertUser(UUID userId) {
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '语义测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test",
			timestamp(), timestamp(), timestamp());
	}

	private void insertVisibleAccountWithPrimary(
		UUID userId, UUID accountId, UUID ledgerId, String currency, String accountType) {
		jdbc.update("""
			INSERT INTO accounts
				(id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'ASSET', ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
			""", accountId, accountType, "语义账户-" + accountId, currency, userId, timestamp(), timestamp());
		UUID membershipId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO account_members
				(id, account_id, user_id, role, status, joined_at, membership_no, version)
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
			VALUES (?, ?, ?, 'PRIMARY', 'ASSET', ?, 'ACTIVE', ?)
			""", ledgerId, accountId, "PRIMARY_" + ledgerId, currency, timestamp());
	}

	private void insertSystemLedger(UUID userId, UUID ledgerId, String code, String nature, String currency) {
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', ?, ?, 'ACTIVE', ?)
			""", ledgerId, userId, code, nature, currency, timestamp());
	}

	private void assertEntry(UUID transactionId, int sequenceNo, UUID ledgerId, String direction, String amount) {
		Map<String, Object> entry = jdbc.queryForMap("""
			SELECT ledger_account_id, direction, amount
			FROM ledger_entries
			WHERE transaction_id = ? AND sequence_no = ?
			""", transactionId, sequenceNo);
		assertEquals(ledgerId, entry.get("ledger_account_id"));
		assertEquals(direction, entry.get("direction"));
		assertEquals(0, new BigDecimal(amount).compareTo((BigDecimal) entry.get("amount")));
	}

	private void assertAdjustment(
		UUID transactionId, String before, String actual, String difference, String reason) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT before_balance, actual_balance, difference_amount, reason
			FROM balance_adjustment_details
			WHERE transaction_id = ?
			""", transactionId);
		assertEquals(0, new BigDecimal(before).compareTo((BigDecimal) row.get("before_balance")));
		assertEquals(0, new BigDecimal(actual).compareTo((BigDecimal) row.get("actual_balance")));
		assertEquals(0, new BigDecimal(difference).compareTo((BigDecimal) row.get("difference_amount")));
		assertEquals(reason, row.get("reason"));
	}

	private int count(String sql, Object... arguments) {
		Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
		return count == null ? 0 : count;
	}

	private Timestamp timestamp() {
		return Timestamp.from(NOW);
	}

	private static Money money(String amount, CurrencyCode currency) {
		return new Money(new BigDecimal(amount), currency);
	}

	private static final class Fixture {
		private final UUID userId;
		private final UUID assetAccountId = UUID.randomUUID();
		private final UUID secondAssetAccountId = UUID.randomUUID();
		private final UUID assetLedgerId = UUID.randomUUID();
		private final UUID secondAssetLedgerId = UUID.randomUUID();
		private final UUID incomeLedgerId = UUID.randomUUID();
		private final UUID expenseLedgerId = UUID.randomUUID();
		private final UUID equityLedgerId = UUID.randomUUID();

		private Fixture(UUID userId) {
			this.userId = userId;
		}
	}
}
