package app.ziji;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.ziji.marketdata.application.MarketDataSyncService;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.marketdata.infrastructure.JavaHttpThsTransport;
import app.ziji.marketdata.infrastructure.PostgresMarketDataQuotaGate;
import app.ziji.marketdata.infrastructure.ThsMarketDataAdapter;
import app.ziji.marketdata.infrastructure.ThsRateLimiter;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA-TS-002（CHG-MD-001 后）：隔离环境真实同花顺股票/ETF/基金冒烟（测试§7.2）。
 * 仅在 ZIJI_THS_SMOKE_ENABLED=true 时执行，使用真实 HTTP 适配器 + 隔离 PostgreSQL；
 * 供应商短时故障（超时/限流/不可用）不作为代码失败并产生明确告警。
 */
@Tag("real-supplier-smoke")
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ZIJI_THS_SMOKE_ENABLED", matches = "true")
class ThsRealSmokePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
	private static final Set<String> TRANSIENT_OUTCOMES = Set.of(
		"QUOTA_EXHAUSTED", "PROVIDER_UNAVAILABLE");

	@Autowired
	private MarketDataCommandStore store;

	@Autowired
	private MarketDataSyncStore syncStore;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void realSupplierIncrementalSyncCoversStockEtfAndFundInIsolatedDatabase() {
		insertInstrumentWithMapping("000001", "STOCK");
		insertInstrumentWithMapping("510300", "ETF");
		insertInstrumentWithMapping("005827", "FUND");

		ThsMarketDataAdapter adapter = new ThsMarketDataAdapter(
			new JavaHttpThsTransport(), objectMapper, Duration.ofSeconds(10), 2,
			new ThsRateLimiter(Clock.systemUTC(), Duration.ofMillis(500), 60),
			new PostgresMarketDataQuotaGate(syncStore, 60), Clock.systemUTC());
		MarketDataSyncService service = new MarketDataSyncService(store, syncStore, adapter, transactions, Clock.systemUTC());

		var summary = service.syncIncremental();

		Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM market_data_sync_runs", Integer.class);
		assertTrue(runs != null && runs >= 1, "真实冒烟必须产生同步运行记录。");
		Integer used = jdbc.queryForObject("SELECT COALESCE(SUM(used_calls), 0) FROM market_data_daily_quotas", Integer.class);
		assertTrue(used != null && used >= 1, "真实冒烟必须产生每日配额使用记录。");

		List<Map<String, Object>> prices = jdbc.queryForList("""
			SELECT i.instrument_type, p.price_type, p.source, p.price, p.raw_payload_hash
			FROM price_snapshots p JOIN instruments i ON i.id = p.instrument_id
			WHERE p.source = 'THS'
			""");
		if (prices.isEmpty()) {
			if (TRANSIENT_OUTCOMES.contains(summary.outcome())) {
				// 供应商短时不可用：按测试§7.2 不作为代码失败，输出明确告警。
				System.err.println("[ThsSmokeWarning] 供应商短时不可用，本轮无价格落库：outcome=" + summary.outcome());
				return;
			}
			fail("全部产品同步失败且非短时故障：outcome=" + summary.outcome());
		}

		for (Map<String, Object> price : prices) {
			String instrumentType = String.valueOf(price.get("instrument_type"));
			String priceType = String.valueOf(price.get("price_type"));
			if ("FUND".equals(instrumentType)) {
				assertEquals("UNIT_NAV", priceType, "场外基金必须落库单位净值。");
			} else {
				assertEquals("CLOSE", priceType, "股票/ETF 必须落库收盘价。");
			}
			BigDecimal value = (BigDecimal) price.get("price");
			assertTrue(value.signum() > 0, "真实价格必须大于零。");
			String payloadHash = String.valueOf(price.get("raw_payload_hash"));
			assertTrue(payloadHash.matches("[0-9a-f]{64}"), "原始载荷 Hash 必须是 64 位十六进制。");
		}
		if (summary.failedCount() > 0) {
			System.err.println("[ThsSmokeWarning] 部分产品同步失败：succeeded=" + summary.succeededCount()
				+ " failed=" + summary.failedCount() + " outcome=" + summary.outcome());
		}
		assertFalse(prices.isEmpty(), "至少一个产品必须成功落库真实行情。");
	}

	private void insertInstrumentWithMapping(String externalCode, String instrumentType) {
		UUID instrumentId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO instruments (id, instrument_type, name, market, currency, status, created_at, updated_at, version)
			VALUES (?, ?, ?, 'CN', 'CNY', 'ACTIVE', ?, ?, 1)
			""", instrumentId, instrumentType, "真实冒烟产品 " + externalCode, Timestamp.from(NOW), Timestamp.from(NOW));
		jdbc.update("""
			INSERT INTO instrument_source_mappings (id, instrument_id, source, external_code, source_market)
			VALUES (?, ?, 'THS', ?, 'CN')
			""", UUID.randomUUID(), instrumentId, externalCode);
	}
}
