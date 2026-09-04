package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
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
import app.ziji.marketdata.application.MarketDataApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B3 投资成交失败原子性与盈亏方向：被拒绝的成交不得留下任何交易、成交或分录事实，
 * 且低价卖出必须得到负的已实现收益（而不是绝对值或被截断为 0）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentTradeFailureAtomicityPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant SELL_AT = Instant.parse("2026-08-11T02:00:00Z");
	private static final Instant DIVIDEND_AT = Instant.parse("2026-08-12T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID ownerId;
	private UUID investmentAccountId;
	private UUID instrumentId;

	@BeforeEach
	void setUpInvestmentAccount() {
		ownerId = insertUser();
		investmentAccountId = createInvestmentAccount(ownerId, "10000.00").account().id();
		instrumentId = marketData.createInstrument(
			ownerId, "STOCK", "B3 原子性测试股票", "CN", "CNY", "investment-atomicity-" + UUID.randomUUID()).id();
		marketData.createManualPrice(ownerId, instrumentId, "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "亏损场景估值", "investment-atomicity-price");
	}

	@Test
	void currencyMismatchLeavesNoFactsAtAll() {
		FactCounts before = counts();

		assertThrows(RuntimeException.class, () -> investments.createTrade(new InvestmentTradeCommand(
			ownerId, UUID.randomUUID(), investmentAccountId, instrumentId, InvestmentSide.BUY,
			new BigDecimal("10"), new BigDecimal("10"), null, "USD", BigDecimal.ZERO, BigDecimal.ZERO,
			BUY_AT, "Asia/Shanghai", "币种不一致")));

		assertEquals(before, counts(), "币种不一致的成交不得留下任何账务事实。");
	}

	@Test
	void unknownInstrumentLeavesNoFactsAtAll() {
		FactCounts before = counts();
		UUID unknownInstrumentId = UUID.randomUUID();

		assertThrows(RuntimeException.class, () -> investments.createTrade(command(
			ownerId, unknownInstrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT)));

		assertEquals(before, counts(), "产品不存在时不得留下任何账务事实。");
	}

	@Test
	void oversellLeavesNoTransactionTradeOrLedgerEntry() {
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT));
		FactCounts before = counts();

		assertThrows(RuntimeException.class, () -> investments.createTrade(command(
			ownerId, instrumentId, InvestmentSide.SELL, "11", "10", null, "0.00", "0.00", SELL_AT)));

		assertEquals(before, counts(), "超量卖出不得留下任何账务事实。");
	}

	@Test
	void rejectedSellKeepsPositionUnchanged() {
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT));

		assertThrows(RuntimeException.class, () -> investments.createTrade(command(
			ownerId, instrumentId, InvestmentSide.SELL, "11", "10", null, "0.00", "0.00", SELL_AT)));

		assertEquals(0, investments.listPositions(ownerId, investmentAccountId, AS_OF, 200).getFirst()
			.quantity().compareTo(new BigDecimal("10")));
	}

	@Test
	void sellingBelowCostYieldsNegativeRealizedProfit() {
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.BUY, "100", "10", null, "5.00", "0.00", BUY_AT));
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.SELL, "40", "8", null, "3.00", "1.00", SELL_AT));
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.DIVIDEND, null, null, "100.00", "0.00", "0.00", DIVIDEND_AT));

		InvestmentPerformanceResult performance = investments.performance(
			ownerId, investmentAccountId, ACCOUNT_OPENED_AT, AS_OF);

		// 卖出毛利 -80（收入 320 - 释放成本 400）再减卖出费用 3 与税费 1。
		assertEquals(0, performance.realizedProfit().compareTo(new BigDecimal("-84.00")),
			"低价卖出必须得到负的已实现收益。");
		assertEquals(0, performance.unrealizedProfit().compareTo(new BigDecimal("300.00")));
		assertEquals(0, performance.dividends().compareTo(new BigDecimal("100.00")));
		assertEquals(0, performance.fees().compareTo(new BigDecimal("8.00")));
		assertEquals(0, performance.taxes().compareTo(new BigDecimal("1.00")));
		assertEquals(0, performance.cumulativeProfit().compareTo(new BigDecimal("311.00")));
	}

	@Test
	void everyPostedInvestmentTransactionBalancesPerCurrency() {
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.BUY, "100", "10", null, "5.00", "0.00", BUY_AT));
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.SELL, "40", "8", null, "3.00", "1.00", SELL_AT));
		investments.createTrade(command(ownerId, instrumentId, InvestmentSide.DIVIDEND, null, null, "100.00", "0.00", "0.00", DIVIDEND_AT));

		// 反例计数必须为 0：任何已入账投资交易都必须在币种内借贷平衡。
		assertEquals(0, count("""
			SELECT count(*) FROM (
				SELECT e.transaction_id
				FROM ledger_entries e
				JOIN transactions t ON t.id = e.transaction_id
				WHERE t.created_by = ? AND t.status = 'POSTED'
				GROUP BY e.transaction_id, e.currency
				HAVING SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END) <> 0
			) unbalanced
			""", ownerId));
	}

	private InvestmentTradeCommand command(
		UUID userId, UUID instrumentId, InvestmentSide side, String quantity, String unitPrice, String dividend,
		String fee, String tax, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), investmentAccountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", new BigDecimal(fee), new BigDecimal(tax), tradeAt, "Asia/Shanghai", "原子性测试");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 原子性账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"), ZONE));
	}

	private FactCounts counts() {
		return new FactCounts(
			count("SELECT count(*) FROM transactions WHERE created_by = ?", ownerId),
			count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId),
			count("SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.created_by = ?", ownerId),
			count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ?", ownerId));
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资原子性用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
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

	/** 一次成交可能触及的全部事实计数，用于证明失败路径没有留下任何残留。 */
	private record FactCounts(int transactions, int trades, int ledgerEntries, int auditLogs) {
	}
}
