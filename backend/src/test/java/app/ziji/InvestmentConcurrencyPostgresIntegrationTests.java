package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentBusinessRuleException;
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
 * BE-INV-008 版本冲突/并发矩阵：同一账户并发卖出的数量校验必须串行化，
 * 恰好一个成交成功，持仓与券商现金最终一致，不得出现负持仓。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentConcurrencyPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_OPENED_AT = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant BUY_AT = Instant.parse("2026-08-10T02:00:00Z");
	private static final Instant SELL_AT = Instant.parse("2026-08-12T02:00:00Z");
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
	void concurrentFullPositionSellsSerializeExactlyOneSucceeds() throws Exception {
		UUID userId = insertUser();
		AccountCreationResult account = createInvestmentAccount(userId, "10000.00");
		UUID accountId = account.account().id();
		backdateMembership(accountId);
		MarketDataApplicationService.InstrumentView instrument = marketData.createInstrument(
			userId, "STOCK", "并发卖出矩阵股票", "CN", "CNY", null, "concurrent-sell-inst");
		marketData.createManualPrice(userId, instrument.id(), "CLOSE", AS_OF.atZone(ZONE).toLocalDate(),
			new BigDecimal("15.00"), "CNY", "并发矩阵估值", "concurrent-sell-price");
		investments.createTrade(command(
			userId, accountId, instrument.id(), InvestmentSide.BUY, "100", "10", null, BUY_AT));

		int threads = 2;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				// 与 HTTP 相同，业务线程必须携带受控请求标识。
				org.slf4j.MDC.put("requestId", "concurrent-sell-" + Thread.currentThread().getName());
				try {
					investments.createTrade(command(
						userId, accountId, instrument.id(), InvestmentSide.SELL, "100", "12", null, SELL_AT));
					succeeded.incrementAndGet();
				} catch (InvestmentBusinessRuleException exception) {
					assertEquals("卖出数量超过当前持仓。", exception.getMessage());
					rejected.incrementAndGet();
				} catch (RuntimeException exception) {
					throw new AssertionError("并发卖出出现未预期异常：" + exception.getClass().getName(), exception);
				} finally {
					org.slf4j.MDC.remove("requestId");
				}
			}));
		}
		ready.await();
		start.countDown();
		for (var future : futures) {
			future.get();
		}
		pool.shutdown();

		assertEquals(1, succeeded.get(), "恰好一个并发卖出必须成功。");
		assertEquals(1, rejected.get(), "超卖请求必须以稳定业务错误被拒绝。");

		// 最终持仓清空；券商现金 = 10000 - 1000 + 1200 = 10200；POSITION_COST 归零。
		var positions = investments.listPositions(userId, accountId, AS_OF, 200);
		assertTrue(positions.isEmpty(), "全部持仓卖出后不得残留持仓。");
		UUID positionCostLedgerId = ledgerAccounts.findPositionCostForVisibleAccount(accountId).orElseThrow().id();
		assertEquals(0, balance(positionCostLedgerId).compareTo(BigDecimal.ZERO), "POSITION_COST 必须归零。");
		var overview = investments.overview(userId, AS_OF);
		assertEquals(0, overview.brokerCash().compareTo(new BigDecimal("10200.00")));
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
			"CNY", BigDecimal.ZERO, BigDecimal.ZERO, tradeAt, "Asia/Shanghai", "并发卖出矩阵");
	}

	private AccountCreationResult createInvestmentAccount(UUID userId, String openingAmount) {
		return accountCreation.createAccountWithOpening(new AccountCreationCommand(
			AccountClass.INVESTMENT, AccountType.FUND, "并发矩阵投资账户", "测试券商", AccountCurrency.CNY, null, userId,
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
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '并发卖出矩阵用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", timestamp(ACCOUNT_OPENED_AT),
			timestamp(ACCOUNT_OPENED_AT), timestamp(ACCOUNT_OPENED_AT));
		return userId;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
