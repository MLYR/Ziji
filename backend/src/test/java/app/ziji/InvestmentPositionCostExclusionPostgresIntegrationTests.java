package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.marketdata.application.MarketDataApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 POSITION_COST 不重复计入总资产（BE-INV-002 / BE-INV-006）：持仓成本科目持有真实余额，
 * 但概览的总资产必须只等于券商现金 + 持仓市值，不得把 POSITION_COST 余额并入。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentPositionCostExclusionPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private LedgerAccountStore ledgerAccounts;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void positionCostBalanceIsExcludedFromTotalInvestmentAssets() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		UUID accountId = account.account().id();
		// 回拨 membership 生效时间，避免 overview 按历史 AS_OF 过滤时查不到账户（时间炸弹）。
		backdateMembership(accountId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "B3 成本不重复计入股票", "CN", "CNY", "investment-position-cost-exclusion");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "成本不重复计入估值", "investment-position-cost-price");

		// 买入 60 股 @10：持仓成本 600，券商现金 9400，持仓市值 60×15=900。
		investments.createTrade(command(
			userId, accountId, instrument.id(), InvestmentSide.BUY, "60", "10", null, "0.00", "0.00", BUY_AT));

		UUID positionCostLedgerId = ledgerAccounts.findPositionCostForVisibleAccount(accountId).orElseThrow().id();
		BigDecimal positionCost = balance(positionCostLedgerId);
		assertTrue(positionCost.compareTo(BigDecimal.ZERO) > 0, "POSITION_COST 科目必须持有真实成本余额。");

		InvestmentApplicationService.InvestmentOverviewResult overview = investments.overview(userId, AS_OF);
		BigDecimal brokerCash = overview.brokerCash();
		BigDecimal marketValue = overview.positionMarketValue();
		BigDecimal total = overview.totalInvestmentAssets();

		// 总资产 = 券商现金 + 持仓市值，不含 POSITION_COST。
		assertEquals(0, total.compareTo(brokerCash.add(marketValue)),
			"总资产必须等于券商现金 + 持仓市值。");
		assertTrue(brokerCash.add(marketValue).add(positionCost).compareTo(total) > 0,
			"含 POSITION_COST 的总和必须严格大于总资产，证明成本科目未被重复计入。");
		assertEquals(0, brokerCash.compareTo(new BigDecimal("9400.00")));
		assertEquals(0, marketValue.compareTo(new BigDecimal("900.00")));
		assertEquals(0, positionCost.compareTo(new BigDecimal("600.00")));
	}

	private void backdateMembership(UUID accountId) {
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE account_id = ?",
			timestamp(ACCOUNT_OPENED_AT), accountId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_from = ?
			WHERE membership_id IN (SELECT id FROM account_members WHERE account_id = ?)
			""", timestamp(ACCOUNT_OPENED_AT), accountId);
	}

	private InvestmentTradeCommand command(
		UUID userId, UUID accountId, UUID instrumentId, InvestmentSide side,
		String quantity, String unitPrice, String dividend, String fee, String tax, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), accountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", new BigDecimal(fee), new BigDecimal(tax), tradeAt, "Asia/Shanghai", "投资成本不重复计入");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 投资账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"), ZONE));
	}

	private BigDecimal balance(UUID ledgerAccountId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN transactions t ON t.id = e.transaction_id
			WHERE e.ledger_account_id = ? AND t.status = 'POSTED'
			""", BigDecimal.class, ledgerAccountId);
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资成本不重复计入用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(ACCOUNT_OPENED_AT),
			timestamp(ACCOUNT_OPENED_AT), timestamp(ACCOUNT_OPENED_AT));
		return userId;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
