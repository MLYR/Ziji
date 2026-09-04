package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import app.ziji.investment.application.InvestmentNotVisibleException;
import app.ziji.investment.application.InvestmentPermissionDeniedException;
import app.ziji.investment.application.InvestmentPositionResult;
import app.ziji.investment.application.InvestmentReturnCalendarResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.investment.domain.ReturnStatus;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 投资对象级授权矩阵：OWNER、EDITOR、VIEWER、已移除成员和无关用户在读写路径上必须得到不同结果，
 * 且不可见账户不得通过收益日历等聚合路径泄漏金额或持仓。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentPermissionMatrixPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");
	private static final YearMonth MONTH = YearMonth.of(2026, 8);

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID ownerId;
	private UUID investmentAccountId;
	private UUID instrumentId;
	private int nextMembershipNo;

	@BeforeEach
	void setUpInvestmentAccount() {
		ownerId = insertUser();
		AccountCreationResult account = createInvestmentAccount(ownerId, "10000.00");
		investmentAccountId = account.account().id();
		instrumentId = marketData.createInstrument(
			ownerId, "STOCK", "B3 权限矩阵股票", "CN", "CNY", "investment-permission-" + UUID.randomUUID()).id();
		// 开户流程把 membership 生效时间写成运行时当前时间，而收益日历与概览按历史时点过滤可见账户。
		// 不回拨生效时间，固定的历史 AS_OF 会让所有主体都查不到账户，权限差异会被掩盖成「都看不见」。
		backdateMembership(investmentAccountId);
		for (int day = 9; day <= 13; day++) {
			marketData.createManualPrice(ownerId, instrumentId, "CLOSE", LocalDate.of(2026, 8, day),
				new BigDecimal("15.00"), "CNY", "权限矩阵估值", "investment-permission-price-" + day);
		}
		// OWNER 的 membership 由开户流程创建，序号从 2 起避免与既有行冲突。
		nextMembershipNo = 2;
	}

	private void backdateMembership(UUID accountId) {
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE account_id = ?",
			timestamp(ACCOUNT_OPENED_AT), accountId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_from = ?
			WHERE membership_id IN (SELECT id FROM account_members WHERE account_id = ?)
			""", timestamp(ACCOUNT_OPENED_AT), accountId);
	}

	@Test
	void ownerAndEditorCanCreateTrades() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		UUID editorId = insertUser();
		membership(investmentAccountId, editorId, "EDITOR", "ACTIVE", null);
		investments.createTrade(command(editorId, InvestmentSide.BUY, "10", "10", BUY_AT));

		assertEquals(2, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId));
	}

	@Test
	void viewerCanReadButCannotCreateTrades() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		UUID viewerId = insertUser();
		membership(investmentAccountId, viewerId, "VIEWER", "ACTIVE", null);

		// 只读成员必须能读取持仓、绩效和收益日历，但写操作必须被显式拒绝。
		List<InvestmentPositionResult> positions = investments.listPositions(viewerId, investmentAccountId, AS_OF, 200);
		assertEquals(1, positions.size());
		assertTrue(investments.listTrades(viewerId, investmentAccountId, null, null, 50).size() >= 1);

		assertThrows(InvestmentPermissionDeniedException.class,
			() -> investments.createTrade(command(viewerId, InvestmentSide.SELL, "1", "10", BUY_AT)));
		assertEquals(1, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId));
	}

	@Test
	void removedMemberCannotReadOrCreateTrades() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		UUID removedId = insertUser();
		membership(investmentAccountId, removedId, "EDITOR", "REMOVED", ACCOUNT_OPENED_AT.plus(1, ChronoUnit.DAYS));

		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.createTrade(command(removedId, InvestmentSide.BUY, "1", "10", BUY_AT)));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.listPositions(removedId, investmentAccountId, AS_OF, 200));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.listTrades(removedId, investmentAccountId, null, null, 50));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.performance(removedId, investmentAccountId, ACCOUNT_OPENED_AT, AS_OF));
	}

	@Test
	void unrelatedUserCannotReadOrCreateTrades() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		UUID strangerId = insertUser();

		// 无关用户必须得到与「不存在」相同的响应，避免通过错误语义枚举账户是否存在。
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.createTrade(command(strangerId, InvestmentSide.BUY, "1", "10", BUY_AT)));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.listPositions(strangerId, investmentAccountId, AS_OF, 200));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.listTrades(strangerId, investmentAccountId, null, null, 50));
		assertThrows(InvestmentNotVisibleException.class,
			() -> investments.performance(strangerId, investmentAccountId, ACCOUNT_OPENED_AT, AS_OF));
	}

	@Test
	void returnCalendarNeverLeaksInvisibleAccountFacts() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		investments.returnCalendar(ownerId, MONTH, "PORTFOLIO", null);

		UUID strangerId = insertUser();
		UUID removedId = insertUser();
		membership(investmentAccountId, removedId, "VIEWER", "REMOVED", ACCOUNT_OPENED_AT.plus(1, ChronoUnit.DAYS));

		for (UUID invisible : List.of(strangerId, removedId)) {
			InvestmentReturnCalendarResult calendar = investments.returnCalendar(invisible, MONTH, "PORTFOLIO", null);
			assertFalse(calendar.days().isEmpty(), "收益日历必须返回整月日期骨架。");
			assertTrue(calendar.days().stream().noneMatch(day -> day.status() == ReturnStatus.CALCULATED),
				"不可见账户不得产生已计算收益日。");
			assertTrue(calendar.profitDayCount() == 0 && calendar.lossDayCount() == 0,
				"不可见账户不得产生盈亏天数统计。");
		}
	}

	@Test
	void instrumentScopeCalendarDoesNotLeakOtherUsersInstrument() {
		investments.createTrade(command(ownerId, InvestmentSide.BUY, "10", "10", BUY_AT));
		UUID strangerId = insertUser();

		// 传入他人产品 ID 时，聚合仍然只在当前用户可见账户内重建，不得回退到全局查询。
		InvestmentReturnCalendarResult calendar = investments.returnCalendar(strangerId, MONTH, "INSTRUMENT", instrumentId);
		assertTrue(calendar.days().stream().noneMatch(day -> day.status() == ReturnStatus.CALCULATED));
		assertTrue(calendar.profitDayCount() == 0 && calendar.lossDayCount() == 0);
	}

	private InvestmentTradeCommand command(UUID userId, InvestmentSide side, String quantity, String unitPrice, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), investmentAccountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			null, "CNY", BigDecimal.ZERO, BigDecimal.ZERO, tradeAt, "Asia/Shanghai", "权限矩阵");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 权限矩阵账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"),
			ZoneId.of("Asia/Shanghai")));
	}

	private void membership(UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		int membershipNo = nextMembershipNo++;
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
				""", membershipId, accountId, userId, role, status, timestamp(ACCOUNT_OPENED_AT), endedAt == null ? null : timestamp(endedAt),
				membershipNo);
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, timestamp(ACCOUNT_OPENED_AT), userId, timestamp(ACCOUNT_OPENED_AT));
		});
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资权限矩阵用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
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
