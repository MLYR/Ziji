package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

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
