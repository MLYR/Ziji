package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.ziji.marketdata.application.MarketDataSyncService;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.RemoteInstrument;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceType;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 增量同步（BE-TS-002/003）：真实 PostgreSQL 下的运行记录、每日配额原子性、
 * 调度锁互斥和增量幂等写入。供应商以假适配器模拟，不访问真实 Tushare。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketDataSyncPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-09-03T09:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

	@Autowired
	private MarketDataCommandStore store;

	@Autowired
	private MarketDataSyncStore syncStore;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@org.junit.jupiter.api.BeforeEach
	void cleanMarketDataTables() {
		jdbc.update("DELETE FROM price_snapshots");
		jdbc.update("DELETE FROM instrument_source_mappings");
		jdbc.update("DELETE FROM instruments");
		jdbc.update("DELETE FROM market_data_sync_runs");
		jdbc.update("DELETE FROM market_data_daily_quotas");
	}

	@Test
	void incrementalSyncPersistsPricesIdempotentlyAndRecordsRun() {
		UUID instrumentId = insertInstrumentWithMapping("000001.SZ", InstrumentType.STOCK);
		FakeSource source = new FakeSource(SourceOutcome.SUCCESS, List.of(
			price(PriceType.CLOSE, LocalDate.of(2026, 9, 2), "10.50"),
			price(PriceType.CLOSE, LocalDate.of(2026, 9, 3), "10.75")));
		MarketDataSyncService service = service(source, NOW);

		var summary = service.syncIncremental();

		assertEquals("SUCCEEDED", summary.status());
		assertEquals(1, summary.succeededCount());
		assertEquals(2, priceCount(instrumentId));
		assertTrue(!source.calls.getFirst().isBefore(LocalDate.of(2026, 8, 4)), "无历史价格时从有限回看窗口开始");
		assertEquals(1, runCount("SUCCEEDED"));
		Timestamp syncedAt = jdbc.queryForObject(
			"SELECT last_synced_at FROM instrument_source_mappings WHERE instrument_id = ?",
			Timestamp.class, instrumentId);
		assertTrue(syncedAt != null, "成功同步必须更新映射的最近同步时间。");

		// 第二轮同价格内容 Hash 相同：不产生新修订；且当日已有价格，不再发起供应商调用。
		var second = service.syncIncremental();
		assertEquals("SUCCEEDED", second.status());
		assertEquals(2, priceCount(instrumentId));
		assertEquals(1, source.fetchCount.get(), "已有当日价格的产品不得重复拉取。");
		assertEquals(2, runCount("SUCCEEDED"));
	}

	@Test
	void quotaReservationIsAtomicAcrossConcurrentCallers() throws InterruptedException {
		LocalDate usageDate = LocalDate.of(2026, 9, 4);
		int callLimit = 50;
		int threads = 20;
		int attemptsPerThread = 3;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger granted = new AtomicInteger();
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				for (int attempt = 0; attempt < attemptsPerThread; attempt++) {
					if (syncStore.reserveQuotaCall(usageDate, callLimit)) {
						granted.incrementAndGet();
					}
				}
			}));
		}
		ready.await();
		start.countDown();
		for (var future : futures) {
			try {
				future.get();
			} catch (java.util.concurrent.ExecutionException exception) {
				throw new RuntimeException(exception);
			}
		}
		pool.shutdown();

		assertEquals(callLimit, granted.get(), "并发保留不得超过配额上限。");
		assertEquals(callLimit, syncStore.usedQuotaCalls(usageDate));
	}

	@Test
	void schedulingLockExcludesConcurrentRunsAndReleasesOnUnlock() {
		assertTrue(syncStore.tryAcquireSyncLock());
		assertFalse(syncStore.tryAcquireSyncLock(), "同一实例不得重复入锁。");
		assertFalse(newOtherStore().tryAcquireSyncLock(), "其他实例必须被会话级 advisory lock 排除。");
		syncStore.releaseSyncLock();
		assertTrue(syncStore.tryAcquireSyncLock(), "释放后可重新获取。");
		syncStore.releaseSyncLock();
	}

	@Test
	void noTokenStopsBatchAndRecordsFailedRun() {
		insertInstrumentWithMapping("510300.SH", InstrumentType.ETF);
		insertInstrumentWithMapping("000001.OF", InstrumentType.FUND);
		FakeSource source = new FakeSource(SourceOutcome.NO_TOKEN, List.of());
		MarketDataSyncService service = service(source, NOW);

		var summary = service.syncIncremental();

		assertEquals("FAILED", summary.status());
		assertEquals("NO_TOKEN", summary.outcome());
		assertEquals(1, source.fetchCount.get(), "无 token 时首个产品即停止整批。");
		assertEquals(1, runCount("FAILED"));
	}

	private MarketDataSyncService service(FakeSource source, Instant now) {
		return new MarketDataSyncService(store, syncStore, source, transactions, Clock.fixed(now, ZoneOffset.UTC));
	}

	private MarketDataSyncStore newOtherStore() {
		return new app.ziji.marketdata.infrastructure.PostgresMarketDataSyncStore(
			jdbc, jdbc.getDataSource());
	}

	private UUID insertInstrumentWithMapping(String externalCode, InstrumentType type) {
		UUID instrumentId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO instruments (id, instrument_type, name, market, currency, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'CNY', 'ACTIVE', ?, ?, 1)
			""", instrumentId, type.name(), "同步测试产品 " + externalCode, "CN", Timestamp.from(NOW), Timestamp.from(NOW));
		jdbc.update("""
			INSERT INTO instrument_source_mappings (id, instrument_id, source, external_code, source_market)
			VALUES (?, ?, 'THS', ?, 'CN')
			""", UUID.randomUUID(), instrumentId, externalCode);
		return instrumentId;
	}

	private int priceCount(UUID instrumentId) {
		Integer count = jdbc.queryForObject(
			"SELECT COUNT(*) FROM price_snapshots WHERE instrument_id = ?", Integer.class, instrumentId);
		return count == null ? 0 : count;
	}

	private int runCount(String status) {
		Integer count = jdbc.queryForObject(
			"SELECT COUNT(*) FROM market_data_sync_runs WHERE status = ?", Integer.class, status);
		return count == null ? 0 : count;
	}

	private static SourcePrice price(PriceType type, LocalDate date, String value) {
		return new SourcePrice(type, date, new BigDecimal(value), "CNY", NOW, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	private static final class FakeSource implements MarketDataSourcePort {

		private final SourceOutcome outcome;
		private final List<SourcePrice> prices;
		private final List<LocalDate> calls = new ArrayList<>();
		private final AtomicInteger fetchCount = new AtomicInteger();

		private FakeSource(SourceOutcome outcome, List<SourcePrice> prices) {
			this.outcome = outcome;
			this.prices = prices;
		}

		@Override
		public SourceResult fetchPrices(Instrument instrument, InstrumentSourceMapping mapping, LocalDate from, LocalDate to) {
			fetchCount.incrementAndGet();
			calls.add(from);
			return outcome == SourceOutcome.SUCCESS
				? new SourceResult(SourceOutcome.SUCCESS, prices, 1, NOW)
				: SourceResult.failure(outcome, 0, NOW);
		}

		@Override
		public List<RemoteInstrument> searchBasics(String query) {
			return List.of();
		}
	}
}
