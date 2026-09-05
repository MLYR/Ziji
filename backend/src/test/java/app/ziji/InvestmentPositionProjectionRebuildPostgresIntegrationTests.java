package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
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
import app.ziji.investment.application.InvestmentPositionResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.marketdata.application.MarketDataApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA-INV-002 投影删除后重建比对：position_snapshots 是可重建投影而非事实源。
 * 注入伪造投影行、物理删除全部投影行后，持仓结果都必须与删除前逐字段一致（差异为 0）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentPositionProjectionRebuildPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant SECOND_BUY_AT = Instant.parse("2026-08-11T02:00:00Z");
	private static final Instant SELL_AT = Instant.parse("2026-08-12T02:00:00Z");
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

	@Test
	void positionProjectionDeletionRebuildsIdenticalPositionsFromLedgerFacts() {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		UUID accountId = account.account().id();
		backdateMembership(accountId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "投影重建比对股票", "CN", "CNY", null, "projection-rebuild-inst");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "投影重建估值", "projection-rebuild-price");

		// 买入 100@10 + 100@11，卖出 50@12：持仓 150，成本基础 2100，平均成本 14。
		investments.createTrade(command(userId, accountId, instrument.id(), InvestmentSide.BUY, "100", "10", null, BUY_AT));
		investments.createTrade(command(userId, accountId, instrument.id(), InvestmentSide.BUY, "100", "11", null, SECOND_BUY_AT));
		investments.createTrade(command(userId, accountId, instrument.id(), InvestmentSide.SELL, "50", "12", null, SELL_AT));

		List<InvestmentPositionResult> baseline = normalizedPositions(userId, accountId);

		// 伪造一条与事实不符的投影行；持仓读取不得受投影内容影响。
		jdbc.update("""
			INSERT INTO position_snapshots
				(investment_account_id, instrument_id, business_date, quantity, cost_basis, average_cost,
				 as_of_change_sequence, calculated_at)
			VALUES (?, ?, ?, 999999, 1, 0.000001, 0, ?)
			""", accountId, instrument.id(), java.sql.Date.valueOf(AS_OF.atZone(ZONE).toLocalDate()), Timestamp.from(AS_OF));
		assertEquals(normalizedPositions(userId, accountId), baseline, "伪造投影不得改变持仓事实读取。");

		// 物理删除全部投影行后从 Ledger 事实重建；逐字段差异必须为 0。
		int deleted = jdbc.update("DELETE FROM position_snapshots WHERE investment_account_id = ?", accountId);
		assertTrue(deleted > 0, "投影删除必须实际发生。");
		assertEquals(normalizedPositions(userId, accountId), baseline, "投影删除后重建结果必须与基线逐字段一致。");
	}

	private List<InvestmentPositionResult> normalizedPositions(UUID userId, UUID accountId) {
		return investments.listPositions(userId, accountId, AS_OF, 200).stream()
			.sorted(Comparator.comparing(InvestmentPositionResult::instrumentId)).toList();
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
		String quantity, String unitPrice, String dividend, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), accountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", BigDecimal.ZERO, BigDecimal.ZERO, tradeAt, "Asia/Shanghai", "投影重建比对");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "投影重建投资账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"), ZONE));
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投影重建用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(ACCOUNT_OPENED_AT),
			timestamp(ACCOUNT_OPENED_AT), timestamp(ACCOUNT_OPENED_AT));
		return userId;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
