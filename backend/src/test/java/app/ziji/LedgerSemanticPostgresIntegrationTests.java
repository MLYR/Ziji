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
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.LedgerOutbox;
import app.ziji.ledger.application.LiabilityBorrowingCommand;
import app.ziji.ledger.application.LiabilityBorrowingWriteDetails;
import app.ziji.ledger.application.LiabilityRepaymentCommand;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.category.application.CategoryStore;
import app.ziji.category.infrastructure.PostgresTagRepository;
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
import app.ziji.ledger.application.RepaymentWriteDetails;
import app.ziji.ledger.application.TransferCommand;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
	private static final UUID FEE_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000202");

	@Autowired
	private LedgerCommandApplicationService service;

	@Autowired
	private LedgerTransactionStore ledgerTransactions;

	@Autowired
	private AccountPostingReferencePort accounts;

	@Autowired
	private AccountPostingAccessPort accountAccess;

	@Autowired
	private CategoryStore categories;

	@Autowired
	private LedgerAccountStore ledgerAccounts;

	@Autowired
	private AuditLogWritePort auditLogs;

	@Autowired
	private LedgerOutbox ledgerOutbox;

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
		assertEquals(2, count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ? AND action = 'TRANSACTION_POSTED'",
			fixture.userId));
		assertEquals(2, count("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (?, ?) AND event_type = 'TransactionPosted'",
			income.transactionId(), expense.transactionId()));
	}

	@Test
	void liabilityCommandsPersistExactPostingsDetailsAndExpenseBoundary() {
		Fixture fixture = fixture();
		Transaction cardExpense = service.postExpense(new ExpenseCommand(
			fixture.userId, fixture.creditCardAccountId, EXPENSE_CATEGORY_ID,
			money("300.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "商户", "信用卡消费"));
		Transaction borrowing = service.postLiabilityBorrowing(new LiabilityBorrowingCommand(
			fixture.userId, fixture.assetAccountId, fixture.loanAccountId, money("10000.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "借款到账"));
		jdbc.update("""
			INSERT INTO liability_details (account_id, current_amount_due, updated_at, version)
			VALUES (?, 1234.00, ?, 1)
			""", fixture.loanAccountId, timestamp());
		Transaction repayment = service.postLiabilityRepayment(new LiabilityRepaymentCommand(
			fixture.userId, fixture.assetAccountId, fixture.loanAccountId,
			money("1000.00", CurrencyCode.CNY), money("50.00", CurrencyCode.CNY), money("2.00", CurrencyCode.CNY),
			EXPENSE_CATEGORY_ID, FEE_CATEGORY_ID, NOW, BUSINESS_DATE, "Asia/Shanghai", "还款"));

		assertEntry(cardExpense.transactionId(), 1, categoryLedger(fixture.userId, EXPENSE_CATEGORY_ID), "D", "300.00");
		assertEntry(cardExpense.transactionId(), 2, fixture.creditCardLedgerId, "C", "300.00");
		assertEquals("TRANSFER", transactionType(borrowing.transactionId()));
		assertEntry(borrowing.transactionId(), 1, fixture.assetLedgerId, "D", "10000.00");
		assertEntry(borrowing.transactionId(), 2, fixture.loanLedgerId, "C", "10000.00");
		Map<String, Object> transfer = jdbc.queryForMap("""
			SELECT from_account_id, to_account_id, from_amount, to_amount, fee_amount
			FROM transfer_details WHERE transaction_id = ?
			""", borrowing.transactionId());
		assertEquals(fixture.loanAccountId, transfer.get("from_account_id"));
		assertEquals(fixture.assetAccountId, transfer.get("to_account_id"));
		assertMoney("10000.00", transfer.get("from_amount"));
		assertMoney("10000.00", transfer.get("to_amount"));
		assertMoney("0.00", transfer.get("fee_amount"));

		assertEquals("REPAYMENT", transactionType(repayment.transactionId()));
		assertEntry(repayment.transactionId(), 1, fixture.loanLedgerId, "D", "1000.00");
		assertEntry(repayment.transactionId(), 2, fixture.assetLedgerId, "C", "1000.00");
		assertEntry(repayment.transactionId(), 3, categoryLedger(fixture.userId, EXPENSE_CATEGORY_ID), "D", "50.00");
		assertEntry(repayment.transactionId(), 4, fixture.assetLedgerId, "C", "50.00");
		assertEntry(repayment.transactionId(), 5, categoryLedger(fixture.userId, FEE_CATEGORY_ID), "D", "2.00");
		assertEntry(repayment.transactionId(), 6, fixture.assetLedgerId, "C", "2.00");
		Map<String, Object> repaymentDetails = jdbc.queryForMap("""
			SELECT liability_account_id, cash_account_id, principal_amount, interest_amount, fee_amount
			FROM repayment_details WHERE transaction_id = ?
			""", repayment.transactionId());
		assertEquals(fixture.loanAccountId, repaymentDetails.get("liability_account_id"));
		assertEquals(fixture.assetAccountId, repaymentDetails.get("cash_account_id"));
		assertMoney("1000.00", repaymentDetails.get("principal_amount"));
		assertMoney("50.00", repaymentDetails.get("interest_amount"));
		assertMoney("2.00", repaymentDetails.get("fee_amount"));
		assertMoney("1234.00", jdbc.queryForObject(
			"SELECT current_amount_due FROM liability_details WHERE account_id = ?",
			BigDecimal.class, fixture.loanAccountId));
		assertEquals(LiabilityBorrowingWriteDetails.class,
			ledgerTransactions.findPostedForMutation(borrowing.transactionId()).orElseThrow().details().getClass());
		RepaymentWriteDetails restoredRepayment = (RepaymentWriteDetails) ledgerTransactions
			.findPostedForMutation(repayment.transactionId()).orElseThrow().details();
		assertEquals(EXPENSE_CATEGORY_ID, restoredRepayment.interestCategoryId());
		assertEquals(FEE_CATEGORY_ID, restoredRepayment.feeCategoryId());

		assertEquals(0, expenseTotal(cardExpense.transactionId()).compareTo(new BigDecimal("300.00")));
		assertEquals(0, expenseTotal(borrowing.transactionId()).compareTo(BigDecimal.ZERO));
		assertEquals(0, expenseTotal(repayment.transactionId()).compareTo(new BigDecimal("52.00")));
		assertEquals(0, incomeTotal(borrowing.transactionId()).compareTo(BigDecimal.ZERO));
		assertEquals(0, incomeTotal(repayment.transactionId()).compareTo(BigDecimal.ZERO));
		assertEquals(3, count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ? AND action = 'TRANSACTION_POSTED'",
			fixture.userId));
		assertEquals(3, count("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (?, ?, ?) AND event_type = 'TransactionPosted'",
			cardExpense.transactionId(), borrowing.transactionId(), repayment.transactionId()));
	}

	@Test
	void liabilityCommandFailuresRollBackFactsAndDerivedCategoryAccounts() {
		Fixture fixture = fixture();
		int transactionsBefore = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);
		int auditsBefore = count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ?", fixture.userId);
		int outboxBefore = count("SELECT count(*) FROM outbox_events");
		int categoryLedgersBefore = categoryLedgerCount(fixture.userId);

		AuditLogWritePort failingAudit = entry -> { throw new IllegalStateException("测试 audit 写入失败。"); };
		assertThrows(IllegalStateException.class, () -> service(fixture, ledgerTransactions, failingAudit, ledgerOutbox)
			.postLiabilityRepayment(repaymentCommand(fixture)));
		assertLiabilityFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore, categoryLedgersBefore);

		LedgerOutbox failingOutbox = event -> { throw new IllegalStateException("测试 outbox 写入失败。"); };
		assertThrows(IllegalStateException.class, () -> service(fixture, ledgerTransactions, auditLogs, failingOutbox)
			.postLiabilityRepayment(repaymentCommand(fixture)));
		assertLiabilityFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore, categoryLedgersBefore);

		LedgerTransactionStore failingLedger = mock(LedgerTransactionStore.class);
		doThrow(new LedgerPersistenceException(new IllegalStateException("测试账务写入失败。")))
			.when(failingLedger).persistPosted(any());
		assertThrows(LedgerPersistenceException.class, () -> service(fixture, failingLedger, auditLogs, ledgerOutbox)
			.postLiabilityRepayment(repaymentCommand(fixture)));
		assertLiabilityFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore, categoryLedgersBefore);
	}

	@Test
	void dualAccountLiabilityCommandsRequireCurrentOwnerOrEditorMembershipOnBothAccounts() {
		Fixture fixture = fixture();
		UUID owner = UUID.randomUUID();
		UUID editor = UUID.randomUUID();
		UUID viewer = UUID.randomUUID();
		UUID left = UUID.randomUUID();
		UUID removed = UUID.randomUUID();
		UUID ended = UUID.randomUUID();
		UUID oneSideOnly = UUID.randomUUID();
		UUID creatorOnly = UUID.randomUUID();
		UUID unrelated = UUID.randomUUID();
		for (UUID userId : List.of(owner, editor, viewer, left, removed, ended, oneSideOnly, creatorOnly, unrelated)) {
			insertUser(userId);
		}
		membership(fixture.assetAccountId, owner, "OWNER", "ACTIVE", null);
		membership(fixture.loanAccountId, owner, "OWNER", "ACTIVE", null);
		membership(fixture.assetAccountId, editor, "EDITOR", "ACTIVE", null);
		membership(fixture.loanAccountId, editor, "EDITOR", "ACTIVE", null);
		membership(fixture.assetAccountId, viewer, "VIEWER", "ACTIVE", null);
		membership(fixture.loanAccountId, viewer, "VIEWER", "ACTIVE", null);
		membership(fixture.assetAccountId, left, "EDITOR", "LEFT", NOW.minusSeconds(60));
		membership(fixture.loanAccountId, left, "EDITOR", "LEFT", NOW.minusSeconds(60));
		membership(fixture.assetAccountId, removed, "EDITOR", "REMOVED", NOW.minusSeconds(60));
		membership(fixture.loanAccountId, removed, "EDITOR", "REMOVED", NOW.minusSeconds(60));
		membership(fixture.assetAccountId, ended, "OWNER", "LEFT", NOW.minusSeconds(1));
		membership(fixture.loanAccountId, ended, "OWNER", "LEFT", NOW.minusSeconds(1));
		membership(fixture.assetAccountId, oneSideOnly, "EDITOR", "ACTIVE", null);
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id IN (?, ?)",
			creatorOnly, fixture.assetAccountId, fixture.loanAccountId);

		assertEquals(TransactionType.TRANSFER, postBorrowing(fixture, owner).type());
		assertEquals(TransactionType.TRANSFER, postBorrowing(fixture, editor).type());
		for (UUID denied : List.of(viewer, left, removed, ended, oneSideOnly, creatorOnly, unrelated)) {
			int before = count("SELECT count(*) FROM transactions WHERE created_by = ?", denied);
			assertThrows(LedgerCommandValidationException.class, () -> postBorrowing(fixture, denied));
			assertEquals(before, count("SELECT count(*) FROM transactions WHERE created_by = ?", denied));
		}
	}

	@Test
	void ledgerAuditAndOutboxFailuresRollBackAllFourFactKinds() {
		Fixture fixture = fixture();
		int transactionsBefore = count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId);
		int auditsBefore = count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ?", fixture.userId);
		int outboxBefore = count("SELECT count(*) FROM outbox_events");

		AuditLogWritePort failingAudit = entry -> { throw new IllegalStateException("测试 audit 写入失败。"); };
		assertThrows(IllegalStateException.class, () -> service(fixture, ledgerTransactions, failingAudit, ledgerOutbox)
			.postExpense(expenseCommand(fixture)));
		assertFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore);

		LedgerOutbox failingOutbox = event -> { throw new IllegalStateException("测试 outbox 写入失败。"); };
		assertThrows(IllegalStateException.class, () -> service(fixture, ledgerTransactions, auditLogs, failingOutbox)
			.postExpense(expenseCommand(fixture)));
		assertFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore);

		LedgerTransactionStore failingLedger = mock(LedgerTransactionStore.class);
		doThrow(new LedgerPersistenceException(new IllegalStateException("测试账务写入失败。")))
			.when(failingLedger).persistPosted(any());
		assertThrows(LedgerPersistenceException.class, () -> service(fixture, failingLedger, auditLogs, ledgerOutbox)
			.postExpense(expenseCommand(fixture)));
		assertFactCounts(fixture, transactionsBefore, auditsBefore, outboxBefore);
	}

	private LedgerCommandApplicationService service(
		Fixture fixture, LedgerTransactionStore store, AuditLogWritePort audits, LedgerOutbox outbox) {
		return new LedgerCommandApplicationService(
			transactionRunner, accounts, accountAccess, categories, new PostgresTagRepository(jdbc), ledgerAccounts,
			store, audits, outbox,
			() -> "postgres-integration-request", new PostingService(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private ExpenseCommand expenseCommand(Fixture fixture) {
		return new ExpenseCommand(
			fixture.userId, fixture.assetAccountId, fixture.expenseLedgerId, EXPENSE_CATEGORY_ID,
			money("1.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "失败注入", "敏感正文不出边界");
	}

	private LiabilityRepaymentCommand repaymentCommand(Fixture fixture) {
		return new LiabilityRepaymentCommand(
			fixture.userId, fixture.assetAccountId, fixture.loanAccountId,
			money("1.00", CurrencyCode.CNY), money("0.01", CurrencyCode.CNY), money("0.01", CurrencyCode.CNY),
			EXPENSE_CATEGORY_ID, FEE_CATEGORY_ID, NOW, BUSINESS_DATE, "Asia/Shanghai", "失败注入");
	}

	private Transaction postBorrowing(Fixture fixture, UUID userId) {
		return service.postLiabilityBorrowing(new LiabilityBorrowingCommand(
			userId, fixture.assetAccountId, fixture.loanAccountId, money("10.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "权限测试"));
	}

	private void assertFactCounts(Fixture fixture, int transactions, int audits, int outbox) {
		assertEquals(transactions, count("SELECT count(*) FROM transactions WHERE created_by = ?", fixture.userId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.created_by = ?", fixture.userId));
		assertEquals(audits, count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ?", fixture.userId));
		assertEquals(outbox, count("SELECT count(*) FROM outbox_events"));
	}

	private void assertLiabilityFactCounts(
		Fixture fixture, int transactions, int audits, int outbox, int categoryLedgers) {
		assertFactCounts(fixture, transactions, audits, outbox);
		assertEquals(categoryLedgers, categoryLedgerCount(fixture.userId));
		assertEquals(0, count("SELECT count(*) FROM repayment_details r JOIN transactions t ON t.id = r.transaction_id WHERE t.created_by = ?",
			fixture.userId));
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
			insertLiabilityAccountWithPrimary(fixture.userId, fixture.creditCardAccountId,
				fixture.creditCardLedgerId, "CNY", "CREDIT_CARD");
			insertLiabilityAccountWithPrimary(fixture.userId, fixture.loanAccountId,
				fixture.loanLedgerId, "CNY", "LOAN");
			insertSystemLedger(fixture.userId, fixture.incomeLedgerId, "INCOME_WAGE", "INCOME", "CNY");
			insertSystemLedger(fixture.userId, fixture.expenseLedgerId, "EXPENSE_FOOD", "EXPENSE", "CNY");
			insertSystemLedger(fixture.userId, fixture.equityLedgerId,
				"EQUITY_BALANCE_ADJUSTMENT", "EQUITY", "CNY");
		});
		return fixture;
	}

	private void insertLiabilityAccountWithPrimary(
		UUID userId, UUID accountId, UUID ledgerId, String currency, String accountType) {
		jdbc.update("""
			INSERT INTO accounts
				(id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'LIABILITY', ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
			""", accountId, accountType, "负债语义账户-" + accountId, currency, userId, timestamp(), timestamp());
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
			VALUES (?, ?, ?, 'PRIMARY', 'LIABILITY', ?, 'ACTIVE', ?)
			""", ledgerId, accountId, "PRIMARY_" + ledgerId, currency, timestamp());
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

	private void membership(UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		Instant joinedAt = NOW.minusSeconds(3600);
		transactionRunner.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status, Timestamp.from(joinedAt),
				endedAt == null ? null : Timestamp.from(endedAt));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, Timestamp.from(joinedAt), userId, Timestamp.from(joinedAt));
		});
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

	private UUID categoryLedger(UUID userId, UUID categoryId) {
		return jdbc.queryForObject("""
			SELECT id FROM ledger_accounts
			WHERE owner_user_id = ? AND code = ? AND currency = 'CNY' AND ledger_role = 'SYSTEM'
			""", UUID.class, userId, "EXPENSE_CATEGORY_" + categoryId);
	}

	private String transactionType(UUID transactionId) {
		return jdbc.queryForObject(
			"SELECT transaction_type FROM transactions WHERE id = ?", String.class, transactionId);
	}

	private BigDecimal expenseTotal(UUID transactionId) {
		BigDecimal total = jdbc.queryForObject("""
			SELECT COALESCE(SUM(e.amount), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND e.direction = 'D' AND la.account_nature = 'EXPENSE'
			""", BigDecimal.class, transactionId);
		return total == null ? BigDecimal.ZERO : total;
	}

	private BigDecimal incomeTotal(UUID transactionId) {
		BigDecimal total = jdbc.queryForObject("""
			SELECT COALESCE(SUM(e.amount), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND e.direction = 'C' AND la.account_nature = 'INCOME'
			""", BigDecimal.class, transactionId);
		return total == null ? BigDecimal.ZERO : total;
	}

	private int categoryLedgerCount(UUID userId) {
		return count("""
			SELECT count(*) FROM ledger_accounts
			WHERE owner_user_id = ? AND code IN (?, ?)
			""", userId, "EXPENSE_CATEGORY_" + EXPENSE_CATEGORY_ID, "EXPENSE_CATEGORY_" + FEE_CATEGORY_ID);
	}

	private static void assertMoney(String expected, Object actual) {
		assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
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
		private final UUID creditCardAccountId = UUID.randomUUID();
		private final UUID loanAccountId = UUID.randomUUID();
		private final UUID assetLedgerId = UUID.randomUUID();
		private final UUID secondAssetLedgerId = UUID.randomUUID();
		private final UUID creditCardLedgerId = UUID.randomUUID();
		private final UUID loanLedgerId = UUID.randomUUID();
		private final UUID incomeLedgerId = UUID.randomUUID();
		private final UUID expenseLedgerId = UUID.randomUUID();
		private final UUID equityLedgerId = UUID.randomUUID();

		private Fixture(UUID userId) {
			this.userId = userId;
		}
	}
}
