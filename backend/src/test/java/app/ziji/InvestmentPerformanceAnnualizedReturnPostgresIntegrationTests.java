package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentPerformanceResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B3 投资绩效年化收益率（BE-INV-007）：XIRR 可用时 annualizedReturn 必须非空且等于资金加权年化收益率，
 * XIRR 不可用时必须返回 null（不伪造 0），以保留失败语义。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentPerformanceAnnualizedReturnPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant DEPOSIT_AT = Instant.parse("2026-08-05T02:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private LedgerCommandApplicationService ledger;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void annualizedReturnEqualsXirrWhenAvailable() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		UUID accountId = account.account().id();
		AccountCreationResult cash = createCashAccount(userId, "10000.00");
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 年化收益率股票", "CN", "CNY", null, "investment-annualized-return");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "年化收益率估值", "investment-annualized-price");

		// 存入一笔资金到投资账户，形成 XIRR 所需的负向现金流；期末值提供正向现金流。
		deposit(userId, cash.account().id(), accountId, "5000.00", DEPOSIT_AT);
		investments.createTrade(command(
			userId, accountId, instrument.id(), InvestmentSide.BUY, "100", "10", null, "0.00", "0.00", BUY_AT));

		InvestmentPerformanceResult performance = investments.performance(userId, accountId, ACCOUNT_OPENED_AT, AS_OF);
		assertNotNull(performance.xirr(), "XIRR 可用时年化收益率必须有值。");
		assertEquals(0, performance.annualizedReturn().compareTo(performance.xirr()),
			"annualizedReturn 必须等于资金加权年化收益率（XIRR）。");
		assertEquals(0, performance.cumulativeProfit().compareTo(new BigDecimal("500.00")),
			"现金 15000 + 持仓市值 1500 - 投入 14000 = 累计收益 500。");
	}

	@Test
	void annualizedReturnIsNullWhenXirrUnavailable() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		UUID accountId = account.account().id();
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 年化收益率失败股票", "CN", "CNY", null, "investment-annualized-fail");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "年化收益率失败估值", "investment-annualized-fail-price");

		// 只有买入、没有账户间转账：XIRR 仅有一个正向期末现金流，现金流不足。
		investments.createTrade(command(
			userId, accountId, instrument.id(), InvestmentSide.BUY, "100", "10", null, "0.00", "0.00", BUY_AT));

		InvestmentPerformanceResult performance = investments.performance(userId, accountId, ACCOUNT_OPENED_AT, AS_OF);
		assertNotNull(performance.returnRate(), "简单收益率在投入资本为正时仍应非空。");
		assertNull(performance.annualizedReturn(), "XIRR 不可用时年化收益率必须返回 null，不得伪造 0。");
	}

	private void deposit(UUID userId, UUID fromAccountId, UUID toAccountId, String amount, Instant at) {
		ledger.postTransfer(new app.ziji.ledger.application.TransferCommand(
			userId, fromAccountId, toAccountId, null,
			new Money(new BigDecimal(amount), CurrencyCode.fromCode("CNY")), null,
			at, at.atZone(ZONE).toLocalDate(), "Asia/Shanghai", "投资存入"));
	}

	private InvestmentTradeCommand command(
		UUID userId, UUID accountId, UUID instrumentId, InvestmentSide side,
		String quantity, String unitPrice, String dividend, String fee, String tax, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), accountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", new BigDecimal(fee), new BigDecimal(tax), tradeAt, "Asia/Shanghai", "投资年化收益率");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		AccountCreationResult result = accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 投资账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"), ZONE));
		// 回拨 membership 保证历史时间点交易与估值具备有效权限
		backdateMembership(result.account().id());
		return result;
	}

	private AccountCreationResult createCashAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.CASH, "B3 现金账户", "测试银行", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "现金期初"), ZONE));
	}

	private void backdateMembership(UUID accountId) {
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE account_id = ?",
			timestamp(ACCOUNT_OPENED_AT), accountId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_from = ?
			WHERE membership_id IN (SELECT id FROM account_members WHERE account_id = ?)
			""", timestamp(ACCOUNT_OPENED_AT), accountId);
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资年化收益率用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(ACCOUNT_OPENED_AT),
			timestamp(ACCOUNT_OPENED_AT), timestamp(ACCOUNT_OPENED_AT));
		return userId;
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
