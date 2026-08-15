package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.category.application.CategoryStore;
import app.ziji.category.application.CategoryReference;
import app.ziji.category.application.CategoryType;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.ledger.domain.Transaction;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 五类账务语义命令的应用编排和边界测试，不替代 PostgreSQL 约束测试。 */
class LedgerCommandApplicationServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000001001");
	private static final UUID ASSET_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000001002");
	private static final UUID SECOND_ASSET_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000001003");
	private static final UUID LIABILITY_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000001004");
	private static final UUID ASSET_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001012");
	private static final UUID SECOND_ASSET_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001013");
	private static final UUID LIABILITY_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001014");
	private static final UUID INCOME_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001015");
	private static final UUID EXPENSE_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001016");
	private static final UUID EQUITY_LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001017");
	private static final UUID INCOME_CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000001021");
	private static final UUID EXPENSE_CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000001022");
	private static final Instant BUSINESS_AT = Instant.parse("2026-08-15T01:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 15);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T02:00:00Z"), ZoneOffset.UTC);

	@Test
	void incomeDebitsAssetCreditsIncomeAndPersistsCategory() {
		Fixture fixture = fixture();

		Transaction transaction = fixture.service.postIncome(new IncomeCommand(
			USER_ID, ASSET_ACCOUNT_ID, INCOME_LEDGER_ID, INCOME_CATEGORY_ID,
			money("8000.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", "发薪方", "八月工资"));

		assertEquals(2, transaction.entries().size());
		assertEquals(LedgerDirection.DEBIT, transaction.entries().get(0).direction());
		assertEquals(ASSET_LEDGER_ID, transaction.entries().get(0).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, transaction.entries().get(1).direction());
		assertEquals(INCOME_LEDGER_ID, transaction.entries().get(1).ledgerAccountId());
		assertEquals(INCOME_CATEGORY_ID, fixture.store.write.categoryId());
		assertEquals(1, fixture.transactions.calls);
	}

	@Test
	void expenseDebitsExpenseAndCreditsLiabilityAccountWithoutNegativeAmount() {
		Fixture fixture = fixture();

		Transaction transaction = fixture.service.postExpense(new ExpenseCommand(
			USER_ID, LIABILITY_ACCOUNT_ID, EXPENSE_LEDGER_ID, EXPENSE_CATEGORY_ID,
			money("300.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", "餐厅", "信用卡消费"));

		assertEquals(LedgerDirection.DEBIT, transaction.entries().get(0).direction());
		assertEquals(EXPENSE_LEDGER_ID, transaction.entries().get(0).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, transaction.entries().get(1).direction());
		assertEquals(LIABILITY_LEDGER_ID, transaction.entries().get(1).ledgerAccountId());
		assertTrue(transaction.entries().stream().allMatch(entry -> entry.amount().amount().signum() > 0));
	}

	@Test
	void refundCreditsOriginalExpenseAndRejectsOverRefund() {
		Fixture fixture = fixture();
		UUID originalTransactionId = UUID.fromString("00000000-0000-0000-0000-000000001031");
		fixture.store.candidate = new LedgerTransactionStore.RefundCandidate(
			originalTransactionId, USER_ID, ASSET_ACCOUNT_ID, EXPENSE_LEDGER_ID, EXPENSE_CATEGORY_ID,
			money("100.00", CurrencyCode.CNY), money("20.00", CurrencyCode.CNY));

		Transaction refund = fixture.service.postRefund(new RefundCommand(
			USER_ID, ASSET_ACCOUNT_ID, originalTransactionId, money("80.00", CurrencyCode.CNY),
			BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", "原餐饮退款"));

		assertEquals(LedgerDirection.DEBIT, refund.entries().get(0).direction());
		assertEquals(ASSET_LEDGER_ID, refund.entries().get(0).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, refund.entries().get(1).direction());
		assertEquals(EXPENSE_LEDGER_ID, refund.entries().get(1).ledgerAccountId());
		assertTrue(fixture.store.write.details() instanceof RefundWriteDetails);
		assertEquals(EXPENSE_CATEGORY_ID, ((RefundWriteDetails) fixture.store.write.details()).categoryId());

		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postRefund(new RefundCommand(
			USER_ID, ASSET_ACCOUNT_ID, originalTransactionId, money("80.01", CurrencyCode.CNY),
			BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", null)));
		assertEquals(2, fixture.transactions.calls);
	}

	@Test
	void transferUsesTwoAssetSidesAndIndependentFeeExpense() {
		Fixture fixture = fixture();

		Transaction transaction = fixture.service.postTransfer(new TransferCommand(
			USER_ID, ASSET_ACCOUNT_ID, SECOND_ASSET_ACCOUNT_ID, EXPENSE_LEDGER_ID, EXPENSE_CATEGORY_ID,
			money("1000.00", CurrencyCode.CNY), money("2.00", CurrencyCode.CNY),
			BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", "银行卡转支付宝"));

		assertEquals(4, transaction.entries().size());
		assertEquals(LedgerDirection.DEBIT, transaction.entries().get(0).direction());
		assertEquals(SECOND_ASSET_LEDGER_ID, transaction.entries().get(0).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, transaction.entries().get(1).direction());
		assertEquals(ASSET_LEDGER_ID, transaction.entries().get(1).ledgerAccountId());
		assertEquals(LedgerDirection.DEBIT, transaction.entries().get(2).direction());
		assertEquals(EXPENSE_LEDGER_ID, transaction.entries().get(2).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, transaction.entries().get(3).direction());
		assertEquals(ASSET_LEDGER_ID, transaction.entries().get(3).ledgerAccountId());
		assertEquals(1, fixture.transactions.calls);
	}

	@Test
	void transferRejectsDifferentCurrenciesBeforePersistence() {
		Fixture fixture = fixture();
		fixture.accounts.accounts.put(SECOND_ASSET_ACCOUNT_ID,
			new AccountPostingReference(SECOND_ASSET_ACCOUNT_ID, "ASSET", "HKD", true));
		fixture.ledgerAccounts.references.put(SECOND_ASSET_LEDGER_ID, reference(
			SECOND_ASSET_LEDGER_ID, SECOND_ASSET_ACCOUNT_ID, null, "SECOND_ASSET", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.ASSET, CurrencyCode.HKD));

		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postTransfer(new TransferCommand(
			USER_ID, ASSET_ACCOUNT_ID, SECOND_ASSET_ACCOUNT_ID, null, null,
			money("1000.00", CurrencyCode.CNY), null, BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", null)));
		assertEquals(1, fixture.transactions.calls);
	}

	@Test
	void adjustmentComputesBeforeFromFactsAndUsesEquityCounterEntry() {
		Fixture fixture = fixture();
		fixture.ledgerAccounts.balances.put(ASSET_LEDGER_ID, money("100.00", CurrencyCode.CNY));

		Transaction transaction = fixture.service.postBalanceAdjustment(new BalanceAdjustmentCommand(
			USER_ID, ASSET_ACCOUNT_ID, EQUITY_LEDGER_ID, money("130.00", CurrencyCode.CNY),
			BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", "盘点差异"));

		assertEquals(LedgerDirection.DEBIT, transaction.entries().get(0).direction());
		assertEquals(ASSET_LEDGER_ID, transaction.entries().get(0).ledgerAccountId());
		assertEquals(LedgerDirection.CREDIT, transaction.entries().get(1).direction());
		assertEquals(EQUITY_LEDGER_ID, transaction.entries().get(1).ledgerAccountId());
		BalanceAdjustmentWriteDetails details = (BalanceAdjustmentWriteDetails) fixture.store.write.details();
		assertEquals(new BigDecimal("100.00"), details.beforeBalance().amount());
		assertEquals(new BigDecimal("30.00"), details.differenceAmount().amount());
	}

	@Test
	void adjustmentRejectsZeroDifferenceAndPermissionDeniesWithoutFactWrite() {
		Fixture fixture = fixture();
		fixture.ledgerAccounts.balances.put(ASSET_LEDGER_ID, money("100.00", CurrencyCode.CNY));
		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postBalanceAdjustment(
			new BalanceAdjustmentCommand(USER_ID, ASSET_ACCOUNT_ID, EQUITY_LEDGER_ID,
				money("100.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", "无差异")));
		assertEquals(1, fixture.transactions.calls);

		fixture.access.allowed = false;
		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postExpense(new ExpenseCommand(
			USER_ID, ASSET_ACCOUNT_ID, EXPENSE_LEDGER_ID, EXPENSE_CATEGORY_ID,
			money("1.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", "商户", null)));
		assertEquals(2, fixture.transactions.calls);
		assertFalse(fixture.store.persisted);
	}

	@Test
	void commandsRejectUnsupportedPrecisionBeforeTransactionCreation() {
		Fixture fixture = fixture();

		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postIncome(new IncomeCommand(
			USER_ID, ASSET_ACCOUNT_ID, INCOME_LEDGER_ID, INCOME_CATEGORY_ID,
			money("1.001", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", null, null)));
		assertEquals(1, fixture.transactions.calls);
		assertFalse(fixture.store.persisted);
	}

	@Test
	void commandsRejectMismatchedPrimaryNatureAndDatabaseBoundTextOverflow() {
		Fixture fixture = fixture();
		fixture.ledgerAccounts.references.put(ASSET_LEDGER_ID, reference(
			ASSET_LEDGER_ID, ASSET_ACCOUNT_ID, null, "ASSET", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.LIABILITY, CurrencyCode.CNY));

		assertThrows(LedgerCommandValidationException.class, () -> fixture.service.postIncome(new IncomeCommand(
			USER_ID, ASSET_ACCOUNT_ID, INCOME_LEDGER_ID, INCOME_CATEGORY_ID,
			money("1.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", "付款方", null)));
		assertFalse(fixture.store.persisted);

		assertThrows(LedgerCommandValidationException.class, () -> new ExpenseCommand(
			USER_ID, ASSET_ACCOUNT_ID, EXPENSE_LEDGER_ID, EXPENSE_CATEGORY_ID,
			money("1.00", CurrencyCode.CNY), BUSINESS_AT, BUSINESS_DATE,
			"Asia/Shanghai", "餐厅".repeat(201), null));
	}

	private static Fixture fixture() {
		Fixture fixture = new Fixture();
		fixture.accounts.accounts.put(ASSET_ACCOUNT_ID,
			new AccountPostingReference(ASSET_ACCOUNT_ID, "ASSET", "CNY", true));
		fixture.accounts.accounts.put(SECOND_ASSET_ACCOUNT_ID,
			new AccountPostingReference(SECOND_ASSET_ACCOUNT_ID, "ASSET", "CNY", true));
		fixture.accounts.accounts.put(LIABILITY_ACCOUNT_ID,
			new AccountPostingReference(LIABILITY_ACCOUNT_ID, "LIABILITY", "CNY", true));
		fixture.ledgerAccounts.references.put(ASSET_LEDGER_ID, reference(
			ASSET_LEDGER_ID, ASSET_ACCOUNT_ID, null, "ASSET", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.ASSET, CurrencyCode.CNY));
		fixture.ledgerAccounts.references.put(SECOND_ASSET_LEDGER_ID, reference(
			SECOND_ASSET_LEDGER_ID, SECOND_ASSET_ACCOUNT_ID, null, "SECOND_ASSET", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.ASSET, CurrencyCode.CNY));
		fixture.ledgerAccounts.references.put(LIABILITY_LEDGER_ID, reference(
			LIABILITY_LEDGER_ID, LIABILITY_ACCOUNT_ID, null, "LIABILITY", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.LIABILITY, CurrencyCode.CNY));
		fixture.ledgerAccounts.references.put(INCOME_LEDGER_ID, reference(
			INCOME_LEDGER_ID, null, USER_ID, "INCOME_WAGE", LedgerAccountRole.SYSTEM,
			LedgerAccountNature.INCOME, CurrencyCode.CNY));
		fixture.ledgerAccounts.references.put(EXPENSE_LEDGER_ID, reference(
			EXPENSE_LEDGER_ID, null, USER_ID, "EXPENSE_FOOD", LedgerAccountRole.SYSTEM,
			LedgerAccountNature.EXPENSE, CurrencyCode.CNY));
		fixture.ledgerAccounts.references.put(EQUITY_LEDGER_ID, reference(
			EQUITY_LEDGER_ID, null, USER_ID, "EQUITY_BALANCE_ADJUSTMENT", LedgerAccountRole.SYSTEM,
			LedgerAccountNature.EQUITY, CurrencyCode.CNY));
		fixture.categories.categories.put(INCOME_CATEGORY_ID,
			new CategoryReference(INCOME_CATEGORY_ID, null, null, CategoryType.INCOME, true));
		fixture.categories.categories.put(EXPENSE_CATEGORY_ID,
			new CategoryReference(EXPENSE_CATEGORY_ID, null, null, CategoryType.EXPENSE, true));
		fixture.service = new LedgerCommandApplicationService(
			fixture.transactions, fixture.accounts, fixture.access, fixture.categories,
			fixture.ledgerAccounts, fixture.store, new PostingService(), CLOCK);
		return fixture;
	}

	private static LedgerAccountReference reference(
		UUID id,
		UUID visibleAccountId,
		UUID ownerUserId,
		String code,
		LedgerAccountRole role,
		LedgerAccountNature nature,
		CurrencyCode currency) {
		return new LedgerAccountReference(id, visibleAccountId, ownerUserId, code, role, nature, currency, true);
	}

	private static Money money(String amount, CurrencyCode currency) {
		return new Money(new BigDecimal(amount), currency);
	}

	private static final class Fixture {
		private final DirectTransactionRunner transactions = new DirectTransactionRunner();
		private final FakeAccountStore accounts = new FakeAccountStore();
		private final FakeAccess access = new FakeAccess();
		private final FakeCategoryStore categories = new FakeCategoryStore();
		private final FakeLedgerAccountStore ledgerAccounts = new FakeLedgerAccountStore();
		private final FakeLedgerTransactionStore store = new FakeLedgerTransactionStore();
		private LedgerCommandApplicationService service;
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		private int calls;

		@Override
		public <T> T required(Supplier<T> action) {
			calls++;
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			calls++;
			action.run();
		}
	}

	private static final class FakeAccountStore implements AccountPostingReferencePort {
		private final Map<UUID, AccountPostingReference> accounts = new HashMap<>();

		@Override
		public Optional<AccountPostingReference> findById(UUID accountId) {
			return Optional.ofNullable(accounts.get(accountId));
		}
	}

	private static final class FakeAccess implements AccountPostingAccessPort {
		private boolean allowed = true;

		@Override
		public boolean mayPost(UUID userId, UUID accountId, Instant effectiveAt) {
			return allowed;
		}
	}

	private static final class FakeCategoryStore implements CategoryStore {
		private final Map<UUID, CategoryReference> categories = new HashMap<>();

		@Override
		public Optional<CategoryReference> findById(UUID categoryId) {
			return Optional.ofNullable(categories.get(categoryId));
		}
	}

	private static final class FakeLedgerAccountStore implements LedgerAccountStore {
		private final Map<UUID, LedgerAccountReference> references = new HashMap<>();
		private final Map<UUID, Money> balances = new HashMap<>();

		@Override
		public Optional<LedgerAccountReference> findById(UUID ledgerAccountId) {
			return Optional.ofNullable(references.get(ledgerAccountId));
		}

		@Override
		public Optional<LedgerAccountReference> findPrimaryForVisibleAccount(UUID accountId) {
			return references.values().stream()
				.filter(reference -> reference.visibleAccountId() != null
					&& reference.visibleAccountId().equals(accountId)
					&& reference.role() == LedgerAccountRole.PRIMARY)
				.findFirst();
		}

		@Override
		public Money currentBalance(UUID ledgerAccountId) {
			return balances.getOrDefault(ledgerAccountId, money("0.00", CurrencyCode.CNY));
		}
	}

	private static final class FakeLedgerTransactionStore implements LedgerTransactionStore {
		private PostedTransactionWrite write;
		private LedgerTransactionStore.RefundCandidate candidate;
		private boolean persisted;

		@Override
		public void persistPosted(PostedTransactionWrite write) {
			this.write = write;
			this.persisted = true;
		}

		@Override
		public Optional<LedgerTransactionStore.RefundCandidate> findRefundCandidate(UUID originalTransactionId) {
			return Optional.ofNullable(candidate);
		}
	}
}
