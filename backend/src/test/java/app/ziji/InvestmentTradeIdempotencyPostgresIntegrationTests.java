package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
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
import app.ziji.investment.application.InvestmentTradeResult;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 投资成交幂等重放与同键异参冲突：真实 PostgreSQL 下，同一 Idempotency-Key 配相同请求 Hash 只执行一次成交
 * （重放返回原交易，不重复落库），配不同请求 Hash 必须 KEY_REUSED 且不改写既有幂等记录、不新增交易事实，
 * 不同 Key 则各自执行。覆盖投资交易已接入的统一幂等服务（InvestmentController.createInvestmentTrade）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentTradeIdempotencyPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-13T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private InvestmentApplicationService investments;

	@Autowired
	private MarketDataApplicationService marketData;

	@Autowired
	private UnifiedIdempotencyService idempotency;

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
			ownerId, "STOCK", "B3 幂等测试股票", "CN", "CNY", null, "investment-idempotency-" + UUID.randomUUID()).id();
		marketData.createManualPrice(ownerId, instrumentId, "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "估值", "investment-idempotency-price");
	}

	@Test
	void sameKeySameHashReplaysWithoutDuplicateTrade() {
		InvestmentTradeCommand command = command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT);

		IdempotencyExecution<InvestmentTradeResult> first = performTrade("same-key-same-hash", command);
		assertTrue(first.executedNow(), "首次请求必须执行。");

		IdempotencyExecution<InvestmentTradeResult> second = performTrade("same-key-same-hash", command);
		assertTrue(second.replayed(), "相同 Key 与 Hash 必须重放而非再次执行。");
		assertNotNull(second.response(), "重放必须返回原响应。");
		assertEquals(first.value().id(), second.response().resourceId(), "重放必须引用同一交易资源。");

		assertEquals(1, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId),
			"重放不得重复落库交易事实。");
	}

	@Test
	void sameKeyDifferentHashIsRejectedWithoutCreatingTrade() {
		InvestmentTradeCommand commandA = command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT);
		IdempotencyExecution<InvestmentTradeResult> first = performTrade("reuse-key-different-hash", commandA);
		assertTrue(first.executedNow(), "首次请求必须执行。");

		InvestmentTradeCommand commandB = command(ownerId, instrumentId, InvestmentSide.BUY, "11", "9", null, "0.00", "0.00", BUY_AT);
		IdempotencyExecution<InvestmentTradeResult> second = performTrade("reuse-key-different-hash", commandB);
		assertTrue(second.status() == IdempotencyExecution.Status.KEY_REUSED, "相同 Key 配不同 Hash 必须判定为 KEY_REUSED。");

		assertEquals(1, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId),
			"同键异参冲突不得新增交易事实，也不得改写既有幂等记录产生的交易。");
	}

	@Test
	void distinctKeyCreatesSeparateTrades() {
		IdempotencyExecution<InvestmentTradeResult> first = performTrade("distinct-key-alpha",
			command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT));
		assertTrue(first.executedNow());
		IdempotencyExecution<InvestmentTradeResult> second = performTrade("distinct-key-bravo",
			command(ownerId, instrumentId, InvestmentSide.BUY, "10", "10", null, "0.00", "0.00", BUY_AT));
		assertTrue(second.executedNow());

		assertEquals(2, count("SELECT count(*) FROM trades WHERE investment_account_id = ?", investmentAccountId),
			"不同 Key 必须各自执行，不被幂等去重。");
	}

	private IdempotencyExecution<InvestmentTradeResult> performTrade(String key, InvestmentTradeCommand command) {
		return idempotency.executeAuthenticated(
			ownerId, 1, "createInvestmentTrade", key,
			IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, "/api/v1/investment-trades",
				tradePayload(command), null),
			() -> {
				InvestmentTradeResult created = investments.createTrade(command);
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "INVESTMENT_TRADE", created.id(),
					new IdempotencyResponse.ResourceReference("/api/v1/investment-trades/" + created.id(), null, null)));
			});
	}

	private static Map<String, Object> tradePayload(InvestmentTradeCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", command.tradeId());
		payload.put("side", command.side());
		payload.put("investmentAccountId", command.investmentAccountId());
		payload.put("instrumentId", command.instrumentId());
		payload.put("quantity", command.quantity());
		payload.put("unitPrice", command.unitPrice());
		payload.put("dividendAmount", command.dividendAmount());
		payload.put("currency", command.currency());
		payload.put("feeAmount", command.feeAmount());
		payload.put("taxAmount", command.taxAmount());
		payload.put("tradeAt", command.tradeAt());
		return payload;
	}

	private InvestmentTradeCommand command(
		UUID userId, UUID instrumentId, InvestmentSide side, String quantity, String unitPrice, String dividend,
		String fee, String tax, Instant tradeAt) {
		return new InvestmentTradeCommand(
			userId, UUID.randomUUID(), investmentAccountId, instrumentId, side,
			quantity == null ? null : new BigDecimal(quantity),
			unitPrice == null ? null : new BigDecimal(unitPrice),
			dividend == null ? null : new BigDecimal(dividend),
			"CNY", new BigDecimal(fee), new BigDecimal(tax), tradeAt, "Asia/Shanghai", "幂等测试");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		AccountCreationResult result = accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "B3 幂等账户", "测试券商", AccountCurrency.CNY, null, userId,
			new AccountOpeningBalance(new BigDecimal(openingAmount), ACCOUNT_OPENED_AT, "投资期初"), ZONE));
		UUID accountId = result.account().id();
		// 回拨 membership 及计入设置生效时间，保证历史时间点的交易具备写入权限
		jdbc.update("UPDATE account_members SET joined_at = ? WHERE account_id = ?",
			timestamp(ACCOUNT_OPENED_AT), accountId);
		jdbc.update("""
			UPDATE account_inclusion_settings SET valid_from = ?
			WHERE membership_id IN (SELECT id FROM account_members WHERE account_id = ?)
			""", timestamp(ACCOUNT_OPENED_AT), accountId);
		return result;
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '投资幂等用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
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
