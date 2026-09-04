package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentReturnCalendarResult;
import app.ziji.investment.application.InvestmentReturnDayDetailsResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.application.InvestmentValuationRevisionPort;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.investment.domain.ReturnStatus;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.marketdata.application.MarketDataValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BE-INV-009/QA-INV-003：收益日历首次生成、价格修正和旧版本保留必须形成完整 revision 链。 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentReturnCalendarRevisionPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final LocalDate PRICE_DATE = LocalDate.of(2026, 8, 10);
	private static final YearMonth MONTH = YearMonth.of(2026, 8);

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private app.ziji.investment.infrastructure.PostgresInvestmentValuationRevisionStore valuationRevisions;

	@Test
	void firstCalendarPublishesRevisionAndPriceCorrectionPreservesOldRevision() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 收益修订测试", "CN", "CNY", "return-calendar-revision");
		MarketDataApplicationService.PriceView firstPrice = marketData.createManualPrice(
			userId, instrument.id(), "CLOSE", PRICE_DATE, new BigDecimal("10.00"), "CNY", "首次价格", "return-price-01");
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), account.account().id(), instrument.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			BUY_AT, "Asia/Shanghai", "收益修订测试买入"));

		InvestmentReturnCalendarResult first = investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
		assertEquals(1, first.valuationRevision());
		assertNotNull(first.recalculatedAt());
		assertEquals(31, count("""
			SELECT count(*) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND valuation_revision = 1
			""", userId));
		InvestmentReturnDayDetailsResult firstDetails = investments.returnDayDetails(
			userId, PRICE_DATE, "PORTFOLIO", null);
		assertEquals(1, firstDetails.valuationRevision());

		marketData.correctPrice(userId, firstPrice.id(), new BigDecimal("12.00"), "修正收盘价", "return-price-02");

		InvestmentReturnCalendarResult second = investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
		assertEquals(2, second.valuationRevision());
		assertTrue(!second.recalculatedAt().isBefore(first.recalculatedAt()));
		assertEquals(31, count("""
			SELECT count(*) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND valuation_revision = 2 AND is_current
			""", userId));
		assertEquals(31, count("""
			SELECT count(*) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND valuation_revision = 1 AND NOT is_current
			""", userId));
		assertEquals(31, count("""
			SELECT count(*) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND is_current
			""", userId));

		Map<String, Object> revisionChain = jdbc.queryForMap("""
			SELECT
				(SELECT id FROM investment_daily_return_snapshots
				 WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
				   AND base_currency = 'CNY' AND business_date = ? AND valuation_revision = 1) AS old_id,
				(SELECT id FROM investment_daily_return_snapshots
				 WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
				   AND base_currency = 'CNY' AND business_date = ? AND valuation_revision = 2) AS new_id,
				(SELECT supersedes_snapshot_id FROM investment_daily_return_snapshots
				 WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
				   AND base_currency = 'CNY' AND business_date = ? AND valuation_revision = 2) AS superseded_id
			""", userId, java.sql.Date.valueOf(PRICE_DATE), userId, java.sql.Date.valueOf(PRICE_DATE),
			userId, java.sql.Date.valueOf(PRICE_DATE));
		assertNotNull(revisionChain.get("old_id"));
		assertNotNull(revisionChain.get("new_id"));
		assertEquals(revisionChain.get("old_id"), revisionChain.get("superseded_id"));

		InvestmentReturnDayDetailsResult secondDetails = investments.returnDayDetails(
			userId, PRICE_DATE, "PORTFOLIO", null);
		assertEquals(2, secondDetails.valuationRevision());
	}

	@Test
	void manualPriceTypeMustMatchInstrumentValuationSemantics() {
		UUID userId = insertUser();
		MarketDataApplicationService.InstrumentView stock = marketData.createInstrument(
			userId, "STOCK", "B3 价格类型测试", "CN", "CNY", "price-type-validation");

		assertThrows(MarketDataValidationException.class, () -> marketData.createManualPrice(
			userId, stock.id(), "UNIT_NAV", PRICE_DATE, new BigDecimal("10.00"), "CNY", "错误类型", "price-type-01"));
	}

	@Test
	void returnCalendarUsesTheHistoricalInclusionRatioForEachBusinessDay() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 历史计入测试", "CN", "CNY", "historical-inclusion");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", PRICE_DATE, new BigDecimal("10.00"),
			"CNY", "历史计入估值", "historical-inclusion-price");
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), account.account().id(), instrument.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			BUY_AT, "Asia/Shanghai", "历史计入买入"));

		Instant ratioChangeAt = LocalDate.of(2026, 8, 16).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
		UUID membershipId = jdbc.queryForObject(
			"SELECT id FROM account_members WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'",
			UUID.class, account.account().id(), userId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_to = ?
			WHERE membership_id = ? AND valid_to IS NULL
			""", Timestamp.from(ratioChangeAt), membershipId);
		jdbc.update("""
			INSERT INTO account_inclusion_settings
				(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 0.500000, ?, ?, ?)
			""", UUID.randomUUID(), membershipId, Timestamp.from(ratioChangeAt), userId, Timestamp.from(ratioChangeAt));

		InvestmentReturnCalendarResult result = investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
		assertEquals(31, result.days().size());
		InvestmentReturnDayDetailsResult before = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 15), "PORTFOLIO", null);
		InvestmentReturnDayDetailsResult after = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 16), "PORTFOLIO", null);

		assertEquals(0, new BigDecimal("10000.00000000").compareTo(before.endValue()));
		assertEquals(0, new BigDecimal("5000.00000000").compareTo(after.endValue()));
	}

	@Test
	void revisionStoreRejectsAnIncompleteMonthBeforeWriting() {
		UUID userId = insertUser();

		assertThrows(IllegalArgumentException.class, () -> valuationRevisions.publish(
			userId, "PORTFOLIO", null, "CNY", MONTH,
			java.util.List.of(new InvestmentValuationRevisionPort.DailySnapshot(
				MONTH.atDay(1), app.ziji.investment.domain.ReturnStatus.NON_TRADING_DAY,
				BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, 0)),
			Instant.parse("2026-09-03T00:00:00Z")));

		assertEquals(0, count("SELECT count(*) FROM investment_daily_return_snapshots WHERE user_id = ?", userId));
	}

	@Test
	void projectionDeletionAllowsDeterministicReconstructionFromFacts() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 投影重建测试", "CN", "CNY", "projection-rebuild-inst");
		marketData.createManualPrice(
			userId, instrument.id(), "CLOSE", PRICE_DATE, new BigDecimal("10.00"), "CNY", "重建价格", "projection-price-01");
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), account.account().id(), instrument.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			BUY_AT, "Asia/Shanghai", "投影重建买入"));

		InvestmentReturnCalendarResult original = investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
		assertEquals(1, original.valuationRevision());
		assertEquals(31, original.days().size());

		List<Map<String, Object>> originalSnapshots = jdbc.queryForList("""
			SELECT business_date, status, begin_value, end_value, net_cash_flow, daily_profit, daily_return_rate, missing_instrument_count
			FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND is_current
			ORDER BY business_date
			""", userId);
		assertEquals(31, originalSnapshots.size());

		// 物理删除快照投影
		int deleted = jdbc.update("DELETE FROM investment_daily_return_snapshots WHERE user_id = ?", userId);
		assertEquals(31, deleted);
		assertEquals(0, count("SELECT count(*) FROM investment_daily_return_snapshots WHERE user_id = ?", userId));

		// 从底层事实重新触发计算与发布
		InvestmentReturnCalendarResult rebuilt = investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
		assertEquals(1, rebuilt.valuationRevision());
		assertEquals(31, rebuilt.days().size());

		List<Map<String, Object>> rebuiltSnapshots = jdbc.queryForList("""
			SELECT business_date, status, begin_value, end_value, net_cash_flow, daily_profit, daily_return_rate, missing_instrument_count
			FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND is_current
			ORDER BY business_date
			""", userId);
		assertEquals(31, rebuiltSnapshots.size());

		// 严格逐日比对，验证投影完全可重现且差异为 0
		for (int i = 0; i < 31; i++) {
			Map<String, Object> before = originalSnapshots.get(i);
			Map<String, Object> after = rebuiltSnapshots.get(i);
			assertEquals(before.get("business_date"), after.get("business_date"));
			assertEquals(before.get("status"), after.get("status"));
			assertBigDecimalEquals((BigDecimal) before.get("begin_value"), (BigDecimal) after.get("begin_value"));
			assertBigDecimalEquals((BigDecimal) before.get("end_value"), (BigDecimal) after.get("end_value"));
			assertBigDecimalEquals((BigDecimal) before.get("net_cash_flow"), (BigDecimal) after.get("net_cash_flow"));
			assertBigDecimalEquals((BigDecimal) before.get("daily_profit"), (BigDecimal) after.get("daily_profit"));
			assertBigDecimalEquals((BigDecimal) before.get("daily_return_rate"), (BigDecimal) after.get("daily_return_rate"));
			assertEquals(before.get("missing_instrument_count"), after.get("missing_instrument_count"));
		}
	}

	@Test
	void returnCalendarCoversDayStatusMatrixForNonTradingDayNoPositionCalculatedPartialAndUnpriced() {
		UUID userId = insertUser();
		// 开立无期初现金的投资账户
		var createdAccount = accountCreation.createAccount(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "状态矩阵账户", "测试券商", AccountCurrency.CNY, null, userId,
			null, ZoneId.of("Asia/Shanghai")));
		UUID membershipId = jdbc.queryForObject(
			"SELECT id FROM account_members WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'",
			UUID.class, createdAccount.id(), userId);
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE id = ?", Timestamp.from(ACCOUNT_OPENED_AT), membershipId);
		jdbc.update("UPDATE account_inclusion_settings SET valid_from = ?, created_at = ? WHERE membership_id = ? AND valid_to IS NULL",
			Timestamp.from(ACCOUNT_OPENED_AT), Timestamp.from(ACCOUNT_OPENED_AT), membershipId);

		MarketDataApplicationService.InstrumentView stockA = marketData.createInstrument(
			userId, "STOCK", "标的A有价格", "CN", "CNY", "inst-status-a");
		MarketDataApplicationService.InstrumentView stockB = marketData.createInstrument(
			userId, "STOCK", "标的B无价格", "CN", "CNY", "inst-status-b");

		// 2026-08-12：stockA 发生日内买入并全额卖出（日初日末持仓为 0，但有交易事件）
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), createdAccount.id(), stockA.id(), InvestmentSide.BUY,
			new BigDecimal("50"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			Instant.parse("2026-08-12T01:30:00Z"), "Asia/Shanghai", "日内买入"));
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), createdAccount.id(), stockA.id(), InvestmentSide.SELL,
			new BigDecimal("50"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			Instant.parse("2026-08-12T06:00:00Z"), "Asia/Shanghai", "日内卖出"));

		// 2026-08-15：分别买入 stockA 与 stockB 各 100 股
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), createdAccount.id(), stockA.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			Instant.parse("2026-08-15T02:00:00Z"), "Asia/Shanghai", "买入标的A"));
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), createdAccount.id(), stockB.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("20.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			Instant.parse("2026-08-15T02:30:00Z"), "Asia/Shanghai", "买入标的B"));

		// 仅提供 stockA 在 08-15 的收盘价；stockB 不录入价格（模拟缺价格）
		marketData.createManualPrice(
			userId, stockA.id(), "CLOSE", LocalDate.of(2026, 8, 15), new BigDecimal("10.00"), "CNY", "A收盘价", "price-a-15");

		// 1. NON_TRADING_DAY：08-02 针对 stockA，无持仓且无交易事件
		InvestmentReturnDayDetailsResult dayNonTrading = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 2), "INSTRUMENT", stockA.id());
		assertEquals(ReturnStatus.NON_TRADING_DAY, dayNonTrading.status());

		// 2. NO_POSITION：08-12 针对 stockA，日初日末持仓为 0 但有成交事件
		InvestmentReturnDayDetailsResult dayNoPosition = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 12), "INSTRUMENT", stockA.id());
		assertEquals(ReturnStatus.NO_POSITION, dayNoPosition.status());

		// 3. CALCULATED：08-15 针对 stockA，有持仓且估值价格完整
		InvestmentReturnDayDetailsResult dayCalculated = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 15), "INSTRUMENT", stockA.id());
		assertEquals(ReturnStatus.CALCULATED, dayCalculated.status());

		// 4. PARTIAL：08-15 组合统计，同时持有 stockA（有价）与 stockB（无价），组合部分缺估值
		InvestmentReturnDayDetailsResult dayPartial = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 15), "PORTFOLIO", null);
		assertEquals(ReturnStatus.PARTIAL, dayPartial.status());

		// 5. UNPRICED：08-15 针对 stockB，单一标的无法估值
		InvestmentReturnDayDetailsResult dayUnpriced = investments.returnDayDetails(
			userId, LocalDate.of(2026, 8, 15), "INSTRUMENT", stockB.id());
		assertEquals(ReturnStatus.UNPRICED, dayUnpriced.status());
	}

	@Test
	void concurrentCalendarPublicationsAreThreadSafeAndIdempotent() throws Exception {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 并发日历测试", "CN", "CNY", "concurrent-cal-inst");
		marketData.createManualPrice(
			userId, instrument.id(), "CLOSE", PRICE_DATE, new BigDecimal("10.00"), "CNY", "并发价格", "concurrent-price-01");
		investments.createTrade(new InvestmentTradeCommand(
			userId, UUID.randomUUID(), account.account().id(), instrument.id(), InvestmentSide.BUY,
			new BigDecimal("100"), new BigDecimal("10.00"), null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO,
			BUY_AT, "Asia/Shanghai", "并发买入"));

		int threads = 4;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<InvestmentReturnCalendarResult>> futures = new ArrayList<>();

		for (int i = 0; i < threads; i++) {
			futures.add(executor.submit(() -> {
				ready.countDown();
				start.await();
				return investments.returnCalendar(userId, MONTH, "PORTFOLIO", null);
			}));
		}

		ready.await();
		start.countDown();

		for (Future<InvestmentReturnCalendarResult> future : futures) {
			InvestmentReturnCalendarResult res = future.get();
			assertEquals(1, res.valuationRevision());
			assertEquals(31, res.days().size());
		}
		executor.shutdown();

		assertEquals(31, count("""
			SELECT count(*) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY' AND is_current
			""", userId));
		assertEquals(1, count("""
			SELECT max(valuation_revision) FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = 'PORTFOLIO' AND instrument_id IS NULL
			  AND base_currency = 'CNY'
			""", userId));
	}

	private static void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
		if (expected == null) {
			org.junit.jupiter.api.Assertions.assertNull(actual);
		} else {
			assertNotNull(actual);
			assertEquals(0, expected.compareTo(actual), () -> "Expected: " + expected + ", actual: " + actual);
		}
	}

	private AccountCreationResult createInvestmentAccount(UUID userId) {
		AccountCreationResult result = accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 收益修订账户", "测试券商", AccountCurrency.CNY, null, userId,
			new app.ziji.account.application.AccountOpeningBalance(new BigDecimal("10000.00"), ACCOUNT_OPENED_AT, "收益修订期初"),
			ZoneId.of("Asia/Shanghai")));
		UUID membershipId = jdbc.queryForObject(
			"SELECT id FROM account_members WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'",
			UUID.class, result.account().id(), userId);
		// 账户创建使用当前服务时钟；测试把初始周期移到固定历史日期，再验证历史计入规则。
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE id = ?", Timestamp.from(ACCOUNT_OPENED_AT), membershipId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_from = ?, created_at = ?
			WHERE membership_id = ? AND valid_to IS NULL
			""", Timestamp.from(ACCOUNT_OPENED_AT), Timestamp.from(ACCOUNT_OPENED_AT), membershipId);
		return result;
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '收益修订测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", Timestamp.from(ACCOUNT_OPENED_AT),
			Timestamp.from(ACCOUNT_OPENED_AT), Timestamp.from(ACCOUNT_OPENED_AT));
		return userId;
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}
}
