package app.ziji;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.IncomeCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LiabilityBorrowingCommand;
import app.ziji.ledger.application.LiabilityRepaymentCommand;
import app.ziji.ledger.application.TransferCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T-LED-012 跨业务组合金标准：混合期初、内部转账、借款本金、还款本金和投资期初入账后，
 * 普通收支只包含真实收入和支出，投资期初只影响 PRIMARY 现金不写 POSITION_COST，
 * 资产/负债/净资产终值与「期初权益 + 真实收支」独立口径一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerMixedGoldenSamplePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-16T04:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);
	private static final UUID INCOME_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000101");
	private static final UUID EXPENSE_CATEGORY_ID =
		UUID.fromString("00000000-0000-4000-8000-000000000201");

	@Autowired
	private LedgerCommandApplicationService service;

	@Autowired
	private AccountStore accountStore;

	@Autowired
	private AccountMemberInitPort memberInit;

	@Autowired
	private AccountLedgerInitializationPort ledgerInit;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void mixedOpeningsTransferBorrowingRepaymentAndInvestmentPrincipalKeepIncomeExpenseAndNetWorthBoundaries() {
		UUID userId = insertUser();
		// 三个期初全部走真实 createAccountWithOpening 路径，不用 JDBC 直写分录。
		AccountCreationResult asset = createWithOpening(userId, AccountClass.ASSET, AccountType.BANK, "10000.00");
		AccountCreationResult investment = createWithOpening(userId, AccountClass.INVESTMENT, AccountType.FUND, "50000.00");
		AccountCreationResult liability = createWithOpening(userId, AccountClass.LIABILITY, AccountType.LOAN, "2000.00");
		UUID incomeLedgerId = insertSystemLedger(userId, "INCOME_WAGE", "INCOME");
		UUID expenseLedgerId = insertSystemLedger(userId, "EXPENSE_FOOD", "EXPENSE");

		// 内部转账 3,000 进入投资账户：投资本金，不是收入。
		Transaction transfer = service.postTransfer(new TransferCommand(
			userId, asset.account().id(), investment.account().id(), null, null,
			money("3000.00", CurrencyCode.CNY), null, NOW, BUSINESS_DATE, "Asia/Shanghai", "转入投资"));
		// 借款本金 10,000 到账：不是收入。
		Transaction borrowing = service.postLiabilityBorrowing(new LiabilityBorrowingCommand(
			userId, asset.account().id(), liability.account().id(), money("10000.00", CurrencyCode.CNY),
			NOW, BUSINESS_DATE, "Asia/Shanghai", "借款到账"));
		// 还款本金 1,000（利息和手续费为零）：不是支出。
		Transaction repayment = service.postLiabilityRepayment(new LiabilityRepaymentCommand(
			userId, asset.account().id(), liability.account().id(),
			money("1000.00", CurrencyCode.CNY), money("0.00", CurrencyCode.CNY), money("0.00", CurrencyCode.CNY),
			null, null, NOW, BUSINESS_DATE, "Asia/Shanghai", "归还本金"));
		// 真实收入 8,000 与真实支出 50。
		Transaction income = service.postIncome(new IncomeCommand(
			userId, asset.account().id(), incomeLedgerId, INCOME_CATEGORY_ID,
			money("8000.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "发薪方", "工资"));
		Transaction expense = service.postExpense(new ExpenseCommand(
			userId, asset.account().id(), expenseLedgerId, EXPENSE_CATEGORY_ID,
			money("50.00", CurrencyCode.CNY), NOW, BUSINESS_DATE, "Asia/Shanghai", "餐厅", "餐饮"));

		// 普通收支只包含真实收入和支出：转账、借款和还款本金对收支口径贡献为 0。
		assertEquals(0, userNatureTotal(userId, "INCOME", "C").compareTo(new BigDecimal("8000.00")));
		assertEquals(0, userNatureTotal(userId, "EXPENSE", "D").compareTo(new BigDecimal("50.00")));
		for (UUID transactionId : List.of(transfer.transactionId(), borrowing.transactionId(), repayment.transactionId())) {
			assertEquals(0, transactionNatureTotal(transactionId, "INCOME", "C").compareTo(BigDecimal.ZERO));
			assertEquals(0, transactionNatureTotal(transactionId, "EXPENSE", "D").compareTo(BigDecimal.ZERO));
		}

		// 投资期初只影响 PRIMARY 现金：期初交易恰好 PRIMARY + EQUITY_OPENING_BALANCE 两条分录。
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
			investment.openingTransactionId()));
		assertEquals(0, count("""
			SELECT count(*) FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE la.visible_account_id = ? AND la.ledger_role = 'POSITION_COST'
			""", investment.account().id()));

		// 每笔已入账交易在 CNY 内借贷平衡。
		assertEquals(0, count("""
			SELECT count(*) FROM (
				SELECT e.transaction_id
				FROM ledger_entries e
				JOIN transactions t ON t.id = e.transaction_id
				WHERE t.created_by = ? AND t.status = 'POSTED'
				GROUP BY e.transaction_id
				HAVING SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END) <> 0
			) unbalanced
			""", userId));

		// 资产/负债终值：资产 76,950 = 23,950(银行) + 53,000(投资 PRIMARY)；负债 11,000。
		BigDecimal assetBalance = primaryBalance(asset.account().id());
		BigDecimal investmentBalance = primaryBalance(investment.account().id());
		BigDecimal liabilityBalance = primaryBalance(liability.account().id());
		assertEquals(0, assetBalance.compareTo(new BigDecimal("23950.00")));
		assertEquals(0, investmentBalance.compareTo(new BigDecimal("53000.00")));
		assertEquals(0, liabilityBalance.compareTo(new BigDecimal("-11000.00")));
		assertEquals(0, userNatureTotal(userId, "ASSET", "D").compareTo(new BigDecimal("76950.00")));

		// 净资产 65,950 与独立口径（期初权益 58,000 + 真实收入 8,000 - 真实支出 50）一致。
		BigDecimal netWorth = userNatureTotal(userId, "ASSET", "D").add(userNatureTotal(userId, "LIABILITY", "D"));
		BigDecimal independent = userNatureTotal(userId, "EQUITY", "C")
			.add(userNatureTotal(userId, "INCOME", "C"))
			.subtract(userNatureTotal(userId, "EXPENSE", "D"));
		assertEquals(0, netWorth.compareTo(new BigDecimal("65950.00")));
		assertEquals(0, independent.compareTo(new BigDecimal("65950.00")));
		assertEquals(0, netWorth.compareTo(independent));
	}

	private AccountCreationResult createWithOpening(
		UUID userId, AccountClass accountClass, AccountType accountType, String amount) {
		return new AccountCreationService(
			transactionRunner, accountStore, memberInit, ledgerInit,
			Clock.fixed(NOW, ZoneOffset.UTC), () -> UUID.randomUUID())
			.createAccountWithOpening(new AccountCreationCommand(
				accountClass, accountType, "金标准期初账户", null, AccountCurrency.CNY, null, userId,
				new AccountOpeningBalance(new BigDecimal(amount), NOW, "期初录入"), ZoneId.of("Asia/Shanghai")));
	}

	private UUID insertSystemLedger(UUID userId, String code, String nature) {
		UUID ledgerId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', ?, 'CNY', 'ACTIVE', ?)
			""", ledgerId, userId, code, nature, Timestamp.from(NOW));
		return ledgerId;
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '金标准测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test",
			Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
		return userId;
	}

	/** 资产/负债按「借-贷」符号返回；负债余额为负数表示正债务。 */
	private BigDecimal primaryBalance(UUID accountId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE la.visible_account_id = ? AND la.ledger_role = 'PRIMARY'
			""", BigDecimal.class, accountId);
	}

	/** 按科目性质与方向聚合该用户全部已入账分录；direction+opposite 组合决定求和口径。 */
	private BigDecimal userNatureTotal(UUID userId, String nature, String direction) {
		return natureTotal("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = ? THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			JOIN transactions t ON t.id = e.transaction_id
			WHERE t.created_by = ? AND t.status = 'POSTED' AND la.account_nature = ?
			""", direction, userId, nature);
	}

	private BigDecimal transactionNatureTotal(UUID transactionId, String nature, String direction) {
		return natureTotal("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = ? THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND la.account_nature = ?
			""", direction, transactionId, nature);
	}

	private BigDecimal natureTotal(String sql, String direction, UUID scopeId, String nature) {
		BigDecimal total = jdbc.queryForObject(sql, BigDecimal.class, direction, scopeId, nature);
		return total == null ? BigDecimal.ZERO : total;
	}

	private int count(String sql, Object... arguments) {
		Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
		return count == null ? 0 : count;
	}

	private static Money money(String amount, CurrencyCode currency) {
		return new Money(new BigDecimal(amount), currency);
	}
}
