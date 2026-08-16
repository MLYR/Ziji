package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.BalanceAdjustmentCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.application.NoTransactionDetails;
import app.ziji.ledger.application.PostedTransactionWrite;
import app.ziji.ledger.application.RevisePostedTransactionCommand;
import app.ziji.ledger.application.RefundCommand;
import app.ziji.ledger.application.TransferCommand;
import app.ziji.ledger.application.TransactionRevisionResult;
import app.ziji.ledger.application.TransactionRevisionDetails;
import app.ziji.ledger.application.TransactionVoidResult;
import app.ziji.ledger.application.VoidPostedTransactionCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntrySpec;
import app.ziji.ledger.domain.LedgerTransactionFactory;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionType;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL/Testcontainers 验证修改、冲正、版本关系和不可变账务事实。 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerReversalPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-16T04:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);
	private static final UUID EXPENSE_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000201");
	private static final UUID INCOME_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000101");

	@Autowired
	private LedgerCommandApplicationService service;

	@Autowired
	private LedgerTransactionStore ledgerTransactions;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void reviseCreatesReversalAndReplacementWhileAllPostedFactsRemainInBalanceRebuild() {
		Fixture fixture = fixture();
		Transaction original = postExpense(fixture, "50.00");

		TransactionRevisionResult result = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, original.transactionId(), 1, NOW.plusSeconds(60), BUSINESS_DATE,
			"Asia/Shanghai", null, "修订商户", "修订支出", "金额录入修正",
			new TransactionRevisionDetails.Expense(
				money("60.00"), fixture.expenseLedgerId, fixture.correctedCategoryId)));

		assertTransaction(original.transactionId(), "SUPERSEDED", original.rootTransactionId(), null, null, 1);
		assertTransaction(result.reversal().transactionId(), "POSTED", result.reversal().transactionId(), null,
			original.transactionId(), 1);
		assertEquals("金额录入修正", jdbc.queryForObject(
			"SELECT note FROM transactions WHERE id = ?", String.class, result.reversal().transactionId()));
		assertEquals(fixture.userId, jdbc.queryForObject(
			"SELECT updated_by FROM transactions WHERE id = ?", UUID.class, original.transactionId()));
		assertEquals(fixture.userId, jdbc.queryForObject(
			"SELECT created_by FROM transactions WHERE id = ?", UUID.class, result.reversal().transactionId()));
		assertTransaction(result.replacement().transactionId(), "POSTED", original.rootTransactionId(),
			original.transactionId(), null, 2);
		assertEntry(result.reversal().transactionId(), 1, fixture.expenseLedgerId, "C", "50.00");
		assertEntry(result.reversal().transactionId(), 2, fixture.assetLedgerId, "D", "50.00");
		assertEntry(result.replacement().transactionId(), 1, fixture.expenseLedgerId, "D", "60.00");
		assertEntry(result.replacement().transactionId(), 2, fixture.assetLedgerId, "C", "60.00");
		assertEquals(1, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			result.replacement().transactionId(), fixture.correctedCategoryId));
		assertEquals(3, count("SELECT count(*) FROM transactions WHERE id IN (?, ?, ?)",
			original.transactionId(), result.reversal().transactionId(), result.replacement().transactionId()));

		// 余额重建只按 posted_at 汇总，故 SUPERSEDED 原分录和冲正分录都继续参与抵消。
		assertBalance(fixture.expenseLedgerId, "60.00");
		assertBalance(fixture.assetLedgerId, "-60.00");

		TransactionRevisionResult second = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, result.replacement().transactionId(), 1, NOW.plusSeconds(120), BUSINESS_DATE,
			"Asia/Shanghai", null, "再次修订商户", "再次修订支出", "二次金额修正",
			new TransactionRevisionDetails.Expense(
				money("70.00"), fixture.expenseLedgerId, fixture.correctedCategoryId)));
		assertTransaction(result.replacement().transactionId(), "SUPERSEDED", original.rootTransactionId(),
			original.transactionId(), null, 2);
		assertTransaction(second.reversal().transactionId(), "POSTED", second.reversal().transactionId(), null,
			result.replacement().transactionId(), 1);
		assertTransaction(second.replacement().transactionId(), "POSTED", original.rootTransactionId(),
			result.replacement().transactionId(), null, 3);
		assertEquals(1, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			original.transactionId(), EXPENSE_CATEGORY_ID));
		assertEquals(0, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			result.replacement().transactionId(), EXPENSE_CATEGORY_ID));
		assertEquals(1, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			result.replacement().transactionId(), fixture.correctedCategoryId));
		assertEquals(1, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			second.replacement().transactionId(), fixture.correctedCategoryId));
		assertEquals(5, count("SELECT count(*) FROM transactions WHERE id IN (?, ?, ?, ?, ?)",
			original.transactionId(), result.reversal().transactionId(), result.replacement().transactionId(),
			second.reversal().transactionId(), second.replacement().transactionId()));
		assertEquals(10, count("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?, ?, ?, ?, ?)",
			original.transactionId(), result.reversal().transactionId(), result.replacement().transactionId(),
			second.reversal().transactionId(), second.replacement().transactionId()));
		assertBalance(fixture.expenseLedgerId, "70.00");
		assertBalance(fixture.assetLedgerId, "-70.00");
	}

	@Test
	void incomeRevisionPersistsNewCategoryAndKeepsIncomeTypeFacts() {
		Fixture fixture = fixture();
		Transaction original = service.postIncome(new app.ziji.ledger.application.IncomeCommand(
			fixture.userId, fixture.assetAccountId, fixture.incomeLedgerId, INCOME_CATEGORY_ID,
			money("100.00"), NOW, BUSINESS_DATE, "Asia/Shanghai", "雇主", "收入"));
		TransactionRevisionResult result = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, original.transactionId(), 1, NOW.plusSeconds(60), BUSINESS_DATE,
			"Asia/Shanghai", "新雇主", null, "新收入", "收入更正",
			new TransactionRevisionDetails.Income(money("120.00"), fixture.incomeLedgerId, INCOME_CATEGORY_ID)));

		assertEquals("INCOME", jdbc.queryForObject(
			"SELECT transaction_type FROM transactions WHERE id = ?", String.class, result.replacement().transactionId()));
		assertEntry(result.replacement().transactionId(), 1, fixture.assetLedgerId, "D", "120.00");
		assertEntry(result.replacement().transactionId(), 2, fixture.incomeLedgerId, "C", "120.00");
		assertEquals(1, count("SELECT count(*) FROM transaction_categories WHERE transaction_id = ? AND category_id = ?",
			result.replacement().transactionId(), INCOME_CATEGORY_ID));
	}

	@Test
	void transferRevisionPersistsTransferDetailsAndRebuildsBothAssetBalances() {
		Fixture fixture = fixture();
		Transaction original = service.postTransfer(new TransferCommand(
			fixture.userId, fixture.assetAccountId, fixture.secondAssetAccountId, null, null,
			money("40.00"), money("0.00"), NOW, BUSINESS_DATE, "Asia/Shanghai", "转账"));
		TransactionRevisionResult result = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, original.transactionId(), 1, NOW.plusSeconds(60), BUSINESS_DATE,
			"Asia/Shanghai", null, null, "新转账", "转账更正",
			new TransactionRevisionDetails.Transfer(
				fixture.assetAccountId, fixture.secondAssetAccountId, null, null, money("55.00"), money("0.00"))));

		Map<String, Object> details = jdbc.queryForMap(
			"SELECT from_account_id, to_account_id, from_amount, to_amount, fee_amount FROM transfer_details WHERE transaction_id = ?",
			result.replacement().transactionId());
		assertEquals(fixture.assetAccountId, details.get("from_account_id"));
		assertEquals(fixture.secondAssetAccountId, details.get("to_account_id"));
		assertEquals(0, new BigDecimal("55.00").compareTo((BigDecimal) details.get("from_amount")));
		assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) details.get("fee_amount")));
		assertEquals(2, count("SELECT count(*) FROM transfer_details WHERE transaction_id IN (?, ?)",
			original.transactionId(), result.replacement().transactionId()));
		assertBalance(fixture.assetLedgerId, "-55.00");
		assertBalance(fixture.secondAssetLedgerId, "55.00");
	}

	@Test
	void refundRevisionPersistsRefundDetailsAndReleasesSupersededAmount() {
		Fixture fixture = fixture();
		Transaction expense = postExpense(fixture, "100.00");
		Transaction refund = service.postRefund(new RefundCommand(
			fixture.userId, fixture.assetAccountId, expense.transactionId(), money("20.00"),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "退款"));
		TransactionRevisionResult result = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, refund.transactionId(), 1, NOW.plusSeconds(60), BUSINESS_DATE,
			"Asia/Shanghai", null, null, "新退款", "退款更正",
			new TransactionRevisionDetails.Refund(fixture.assetAccountId, expense.transactionId(), money("10.00"))));

		Map<String, Object> details = jdbc.queryForMap(
			"SELECT original_transaction_id, category_id FROM refund_details WHERE transaction_id = ?",
			result.replacement().transactionId());
		assertEquals(expense.transactionId(), details.get("original_transaction_id"));
		assertEquals(EXPENSE_CATEGORY_ID, details.get("category_id"));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND status = 'SUPERSEDED'",
			refund.transactionId()));
		assertEquals(2, count("SELECT count(*) FROM refund_details WHERE transaction_id IN (?, ?)",
			refund.transactionId(), result.replacement().transactionId()));
		assertBalance(fixture.expenseLedgerId, "90.00");
		assertBalance(fixture.assetLedgerId, "-90.00");
	}

	@Test
	void balanceAdjustmentRevisionAndVoidKeepAdjustmentDetailsAndRestoreBalance() {
		Fixture fixture = fixture();
		Transaction original = service.postBalanceAdjustment(new BalanceAdjustmentCommand(
			fixture.userId, fixture.assetAccountId, fixture.equityLedgerId, money("50.00"),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "首次盘点"));
		TransactionRevisionResult revised = service.revisePostedTransaction(new RevisePostedTransactionCommand(
			fixture.userId, original.transactionId(), 1, NOW.plusSeconds(60), BUSINESS_DATE,
			"Asia/Shanghai", null, null, "新盘点", "盘点更正",
			new TransactionRevisionDetails.BalanceAdjustment(
				fixture.assetAccountId, fixture.equityLedgerId, money("30.00"), "修正盘点")));
		Map<String, Object> revisedDetails = jdbc.queryForMap(
			"SELECT before_balance, actual_balance, difference_amount, reason FROM balance_adjustment_details WHERE transaction_id = ?",
			revised.replacement().transactionId());
		assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) revisedDetails.get("before_balance")));
		assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) revisedDetails.get("actual_balance")));
		assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) revisedDetails.get("difference_amount")));
		assertEquals("修正盘点", revisedDetails.get("reason"));
		assertEquals(2, count("SELECT count(*) FROM balance_adjustment_details WHERE transaction_id IN (?, ?)",
			original.transactionId(), revised.replacement().transactionId()));
		assertBalance(fixture.assetLedgerId, "30.00");

		TransactionVoidResult voided = service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, revised.replacement().transactionId(), 1, "作废盘点"));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND status = 'REVERSED'",
			revised.replacement().transactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
			voided.reversal().transactionId()));
		assertBalance(fixture.assetLedgerId, "0.00");
	}

	@Test
	void voidCreatesReversalKeepsOriginalEntriesAndDatabaseRejectsFactMutationOrDeletion() {
		Fixture fixture = fixture();
		Transaction original = postExpense(fixture, "50.00");

		TransactionVoidResult result = service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, original.transactionId(), 1, "重复支出"));

		assertTransaction(original.transactionId(), "REVERSED", original.rootTransactionId(), null, null, 1);
		assertTransaction(result.reversal().transactionId(), "POSTED", result.reversal().transactionId(), null,
			original.transactionId(), 1);
		assertEquals("重复支出", jdbc.queryForObject(
			"SELECT note FROM transactions WHERE id = ?", String.class, result.reversal().transactionId()));
		assertEquals(fixture.userId, jdbc.queryForObject(
			"SELECT updated_by FROM transactions WHERE id = ?", UUID.class, original.transactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", original.transactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", result.reversal().transactionId()));
		assertBalance(fixture.expenseLedgerId, "0.00");
		assertBalance(fixture.assetLedgerId, "0.00");

		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> jdbc.update(
			"UPDATE transactions SET note = '禁止修改' WHERE id = ?", original.transactionId())));
		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> jdbc.update(
			"UPDATE ledger_entries SET amount = 1.00 WHERE transaction_id = ? AND sequence_no = 1",
			result.reversal().transactionId())));
		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> jdbc.update(
			"DELETE FROM ledger_entries WHERE transaction_id = ? AND sequence_no = 1", original.transactionId())));
		assertThrows(DataAccessException.class, () -> transactionRunner.required(() -> jdbc.update(
			"DELETE FROM transactions WHERE id = ?", original.transactionId())));
		int transactionCount = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);
		assertThrows(LedgerCommandValidationException.class, () -> service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, original.transactionId(), 2, "重复作废")));
		assertEquals(transactionCount, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
		assertThrows(LedgerCommandValidationException.class, () -> service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, result.reversal().transactionId(), 1, "不能作废冲正")));
		assertEquals(transactionCount, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
	}

	@Test
	void staleUnauthorizedAndDependentPostedTransactionsAreRejectedBeforeNewFacts() {
		Fixture fixture = fixture();
		Transaction original = postExpense(fixture, "50.00");
		int before = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);

		assertThrows(LedgerCommandValidationException.class, () -> service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, original.transactionId(), 2, "陈旧版本")));
		assertEquals(before, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));

		UUID unrelatedUser = UUID.randomUUID();
		transactionRunner.required(() -> insertUser(unrelatedUser));
		assertThrows(LedgerCommandValidationException.class, () -> service.voidPostedTransaction(
			new VoidPostedTransactionCommand(unrelatedUser, original.transactionId(), 1, "无权作废")));
		assertEquals(before, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));

		service.postRefund(new RefundCommand(
			fixture.userId, fixture.assetAccountId, original.transactionId(), money("1.00"),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "关联退款"));
		int afterRefund = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);
		assertThrows(LedgerCommandValidationException.class, () -> service.revisePostedTransaction(
			new RevisePostedTransactionCommand(
				fixture.userId, original.transactionId(), 1, NOW, BUSINESS_DATE, "Asia/Shanghai", null, "商户", "修订",
				"关联退款", new TransactionRevisionDetails.Expense(
					money("51.00"), fixture.expenseLedgerId, EXPENSE_CATEGORY_ID))));
		assertEquals(afterRefund, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
		assertThrows(LedgerCommandValidationException.class, () -> service.voidPostedTransaction(
			new VoidPostedTransactionCommand(fixture.userId, original.transactionId(), 1, "关联退款")));
		assertEquals(afterRefund, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
	}

	@Test
	void failureAfterReversalInsertRollsBackReversalStatusTransitionAndReplacementTogether() {
		Fixture fixture = fixture();
		Transaction original = postExpense(fixture, "50.00");
		LedgerTransactionFactory factory = new LedgerTransactionFactory(new PostingService());
		Transaction reversal = factory.createReversal(original, UUID.randomUUID(), NOW.plusSeconds(1));
		UUID replacementId = UUID.randomUUID();
		Transaction invalidReplacement = factory.createPostedVersion(
			replacementId, TransactionType.EXPENSE, TransactionSource.MANUAL, NOW.plusSeconds(2), BUSINESS_DATE,
			"Asia/Shanghai", NOW.plusSeconds(2), original.rootTransactionId(), original.transactionId(), 2, List.of(
				new LedgerEntrySpec(fixture.expenseLedgerId, LedgerDirection.DEBIT, money("60.00")),
				new LedgerEntrySpec(UUID.randomUUID(), LedgerDirection.CREDIT, money("60.00"))));

		assertThrows(LedgerPersistenceException.class, () -> transactionRunner.required(() ->
			ledgerTransactions.persistRevision(new LedgerTransactionStore.TransactionRevisionWrite(
				original.transactionId(), 1, "持久化回滚测试", reversal,
				new PostedTransactionWrite(invalidReplacement, fixture.userId, null, "失败商户", null, null,
					new NoTransactionDetails())))));
		assertTransaction(original.transactionId(), "POSTED", original.rootTransactionId(), null, null, 1);
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", reversal.transactionId()));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", replacementId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?, ?)",
			reversal.transactionId(), replacementId));
	}

	private Transaction postExpense(Fixture fixture, String amount) {
		return service.postExpense(new ExpenseCommand(
			fixture.userId, fixture.assetAccountId, fixture.expenseLedgerId, EXPENSE_CATEGORY_ID, money(amount),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "原商户", "原支出"));
	}

	private Fixture fixture() {
		Fixture fixture = new Fixture(UUID.randomUUID());
		transactionRunner.required(() -> {
			insertUser(fixture.userId);
			insertVisibleAccountWithPrimary(fixture.userId, fixture.assetAccountId, fixture.assetLedgerId, "CNY", "BANK");
			insertVisibleAccountWithPrimary(fixture.userId, fixture.secondAssetAccountId,
				fixture.secondAssetLedgerId, "CNY", "ALIPAY");
			insertSystemLedger(fixture.userId, fixture.expenseLedgerId, "EXPENSE_FOOD", "EXPENSE", "CNY");
			insertSystemLedger(fixture.userId, fixture.incomeLedgerId, "INCOME_WAGE", "INCOME", "CNY");
			insertSystemLedger(fixture.userId, fixture.equityLedgerId,
				"EQUITY_BALANCE_ADJUSTMENT", "EQUITY", "CNY");
			insertExpenseCategory(fixture.userId, fixture.correctedCategoryId, "交通");
		});
		return fixture;
	}

	private void insertUser(UUID userId) {
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '冲正测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(), timestamp(), timestamp());
	}

	private void insertVisibleAccountWithPrimary(
		UUID userId, UUID accountId, UUID ledgerId, String currency, String accountType) {
		jdbc.update("""
			INSERT INTO accounts
				(id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'ASSET', ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
			""", accountId, accountType, "冲正账户-" + accountId, currency, userId, timestamp(), timestamp());
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

	private void insertExpenseCategory(UUID userId, UUID categoryId, String name) {
		jdbc.update("""
			INSERT INTO categories
				(id, owner_user_id, account_id, category_type, parent_id, name, name_normalized,
				 status, merged_into_id, created_at, updated_at, version)
			VALUES (?, ?, NULL, 'EXPENSE', NULL, ?, ?, 'ACTIVE', NULL, ?, ?, 1)
			""", categoryId, userId, name, name, timestamp(), timestamp());
	}

	private void assertTransaction(
		UUID transactionId, String status, UUID rootId, UUID previousVersionId, UUID reversalOfId, int versionNo) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT status, root_transaction_id, previous_version_id, reversal_of_id, version_no
			FROM transactions WHERE id = ?
			""", transactionId);
		assertEquals(status, row.get("status"));
		assertEquals(rootId, row.get("root_transaction_id"));
		assertEquals(previousVersionId, row.get("previous_version_id"));
		assertEquals(reversalOfId, row.get("reversal_of_id"));
		assertEquals(versionNo, row.get("version_no"));
	}

	private void assertEntry(UUID transactionId, int sequenceNo, UUID ledgerAccountId, String direction, String amount) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT ledger_account_id, direction, amount
			FROM ledger_entries WHERE transaction_id = ? AND sequence_no = ?
			""", transactionId, sequenceNo);
		assertEquals(ledgerAccountId, row.get("ledger_account_id"));
		assertEquals(direction, row.get("direction"));
		assertEquals(0, new BigDecimal(amount).compareTo((BigDecimal) row.get("amount")));
	}

	private void assertBalance(UUID ledgerAccountId, String amount) {
		BigDecimal balance = jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN transactions t ON t.id = e.transaction_id
			WHERE e.ledger_account_id = ? AND t.posted_at IS NOT NULL
			""", BigDecimal.class, ledgerAccountId);
		assertEquals(0, new BigDecimal(amount).compareTo(balance));
	}

	private int count(String sql, Object... arguments) {
		Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
		return value == null ? 0 : value;
	}

	private Timestamp timestamp() {
		return Timestamp.from(NOW);
	}

	private static Money money(String amount) {
		return new Money(new BigDecimal(amount), CurrencyCode.CNY);
	}

	private static final class Fixture {
		private final UUID userId;
		private final UUID assetAccountId = UUID.randomUUID();
		private final UUID assetLedgerId = UUID.randomUUID();
		private final UUID secondAssetAccountId = UUID.randomUUID();
		private final UUID secondAssetLedgerId = UUID.randomUUID();
		private final UUID expenseLedgerId = UUID.randomUUID();
		private final UUID incomeLedgerId = UUID.randomUUID();
		private final UUID equityLedgerId = UUID.randomUUID();
		private final UUID correctedCategoryId = UUID.randomUUID();

		private Fixture(UUID userId) {
			this.userId = userId;
		}
	}
}
