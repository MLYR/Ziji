package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentPerformanceResult;
import app.ziji.investment.application.InvestmentPositionResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.application.InvestmentTradeResult;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.marketdata.application.MarketDataApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B3 投资账务金标准：成交明细、Ledger 分录、持仓成本和收益结果必须从同一组事实重建。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentGoldenSamplePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant SELL_AT = Instant.parse("2026-08-11T02:00:00Z");
	private static final Instant DIVIDEND_AT = Instant.parse("2026-08-12T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private LedgerAccountStore ledgerAccounts;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void buyPartialSellDividendAndFeesKeepCashCostProfitAndEntryBalanceConsistent() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 测试股票", "CN", "CNY", "investment-golden-sample");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "金标准估值", "investment-price-01");

		InvestmentTradeResult buy = investments.createTrade(command(
			userId, account.account().id(), instrument.id(), InvestmentSide.BUY, "100", "10", null, "5.00", "0.00", BUY_AT));
		InvestmentTradeResult sell = investments.createTrade(command(
			userId, account.account().id(), instrument.id(), InvestmentSide.SELL, "40", "12", null, "3.00", "1.00", SELL_AT));
		InvestmentTradeResult dividend = investments.createTrade(command(
			userId, account.account().id(), instrument.id(), InvestmentSide.DIVIDEND, null, null, "100.00", "0.00", "0.00", DIVIDEND_AT));

		assertEquals(3, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", account.account().id()));
		assertEquals(1, count("SELECT count(*) FROM trades WHERE id = ? AND transaction_id = ?", buy.id(), buy.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM trades WHERE id = ? AND transaction_id = ?", sell.id(), sell.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM trades WHERE id = ? AND transaction_id = ?", dividend.id(), dividend.transactionId()));

		UUID primaryLedgerId = ledgerAccounts.findPrimaryForVisibleAccount(account.account().id()).orElseThrow().id();
		UUID positionCostLedgerId = ledgerAccounts.findPositionCostForVisibleAccount(account.account().id()).orElseThrow().id();
		assertEquals(0, balance(primaryLedgerId).compareTo(new BigDecimal("9571.00")));
		assertEquals(0, balance(positionCostLedgerId).compareTo(new BigDecimal("600.00")));

		Map<String, Object> sellEntries = jdbc.queryForMap("""
			SELECT
				SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE 0 END) AS debit_total,
				SUM(CASE WHEN e.direction = 'C' THEN e.amount ELSE 0 END) AS credit_total
			FROM ledger_entries e WHERE e.transaction_id = ?
			""", sell.transactionId());
		assertEquals(0, ((BigDecimal) sellEntries.get("debit_total")).compareTo((BigDecimal) sellEntries.get("credit_total")));
		assertEquals(4, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", buy.transactionId()));
		assertEquals(7, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", sell.transactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", dividend.transactionId()));

		InvestmentApplicationService.InvestmentOverviewResult overview = investments.overview(userId, AS_OF);
		assertEquals(0, overview.brokerCash().compareTo(new BigDecimal("9571.00")));
		assertEquals(0, overview.positionMarketValue().compareTo(new BigDecimal("900.00")));
		assertEquals(0, overview.totalInvestmentAssets().compareTo(new BigDecimal("10471.00")));

		InvestmentPerformanceResult performance = investments.performance(
			userId, account.account().id(), ACCOUNT_OPENED_AT, AS_OF);
		assertEquals(0, performance.realizedProfit().compareTo(new BigDecimal("76.00")));
		assertEquals(0, performance.unrealizedProfit().compareTo(new BigDecimal("300.00")));
		assertEquals(0, performance.dividends().compareTo(new BigDecimal("100.00")));
		assertEquals(0, performance.fees().compareTo(new BigDecimal("8.00")));
		assertEquals(0, performance.taxes().compareTo(new BigDecimal("1.00")));
		assertEquals(0, performance.cumulativeProfit().compareTo(new BigDecimal("471.00")));

		List<InvestmentPositionResult> positions = investments.listPositions(
			userId, account.account().id(), AS_OF, 200);
		assertEquals(1, positions.size());
		assertEquals(0, positions.getFirst().quantity().compareTo(new BigDecimal("60")));
		assertEquals(0, positions.getFirst().costBasis().compareTo(new BigDecimal("600.00")));
		assertEquals(0, positions.getFirst().averageCost().compareTo(new BigDecimal("10.000000000000")));
		assertEquals(0, positions.getFirst().marketValue().compareTo(new BigDecimal("900.00")));

		assertEquals(4, count("""
			SELECT count(*) FROM audit_logs
			WHERE actor_user_id = ? AND action = 'TRANSACTION_POSTED'
			""", userId));
		assertEquals(3, count("""
			SELECT count(*) FROM outbox_events
			WHERE aggregate_id IN (?, ?, ?) AND event_type = 'TransactionPosted'
			""", buy.transactionId(), sell.transactionId(), dividend.transactionId()));
	}

	@Test
	void rejectedOversellLeavesNoInvestmentFactsOrLedgerEntries() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "1000.00");
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "ETF", "B3 负持仓测试", "CN", "CNY", "investment-oversell-sample");
		investments.createTrade(command(
			userId, account.account().id(), instrument.id(), InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT));

		int transactionsBefore = count("SELECT count(*) FROM transactions WHERE created_by = ?", userId);
		int tradesBefore = count("SELECT count(*) FROM trades WHERE investment_account_id = ?", account.account().id());
		assertThrows(RuntimeException.class, () -> investments.createTrade(command(
			userId, account.account().id(), instrument.id(), InvestmentSide.SELL, "11", "10", null, "0.00", "0.00", SELL_AT)));
		assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions WHERE created_by = ?", userId));
		assertEquals(tradesBefore, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", account.account().id()));
	}

	private InvestmentTradeCommand command(
		UUID userId,
		UUID accountId,
		UUID instrumentId,
		InvestmentSide side,
		String quantity,
		String unitPrice,
		String dividend,
		String fee,
		String tax,
		Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), accountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", new BigDecimal(fee), new BigDecimal(tax), tradeAt, "Asia/Shanghai", "投资金标准");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 投资账户", "测试券商", AccountCurrency.CNY, null, userId,
			new app.ziji.account.application.AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"),
			ZoneId.of("Asia/Shanghai")));
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资金标准用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(ACCOUNT_OPENED_AT),
			timestamp(ACCOUNT_OPENED_AT), timestamp(ACCOUNT_OPENED_AT));
		return userId;
	}

	private BigDecimal balance(UUID ledgerAccountId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN transactions t ON t.id = e.transaction_id
			WHERE e.ledger_account_id = ? AND t.status = 'POSTED'
			""", BigDecimal.class, ledgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
