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
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

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
			// 真实冒烟按每产品至少 2 秒间隔请求，避免对公开端点形成突发（500ms 间隔实测触发 429）。
			new ThsRateLimiter(Clock.systemUTC(), Duration.ofSeconds(2), 60),
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
		boolean transientOutcome = TRANSIENT_OUTCOMES.contains(summary.outcome());
		if (prices.isEmpty()) {
			// 供应商短时不可用：按测试§7.2 不作为代码失败，abort 为 skipped 且不得计为验收通过。
			Assumptions.assumeTrue(!transientOutcome,
				"[ThsSmokeSkip] 供应商短时不可用，本轮无价格落库，不构成 QA-TS-002 验收证据：outcome=" + summary.outcome());
			fail("全部产品同步失败且非短时故障：outcome=" + summary.outcome());
		}

		for (Map<String, Object> price : prices) {
			String instrumentType = String.valueOf(price.get("instrument_type"));
			String priceType = String.valueOf(price.get("price_type"));
			BigDecimal value = (BigDecimal) price.get("price");
			assertTrue(value.signum() > 0, "真实价格必须大于零。");
			String payloadHash = String.valueOf(price.get("raw_payload_hash"));
			assertTrue(payloadHash.matches("[0-9a-f]{64}"), "原始载荷 Hash 必须是 64 位十六进制。");
		}
		// 三类产品必须各自以正确价格类型落库；部分失败同样不构成验收证据。
		boolean complete = prices.stream().anyMatch(isType("STOCK", "CLOSE"))
			&& prices.stream().anyMatch(isType("ETF", "CLOSE"))
			&& prices.stream().anyMatch(isType("FUND", "UNIT_NAV"));
		Assumptions.assumeTrue(!transientOutcome || complete,
			"[ThsSmokeSkip] 短时故障导致三类产品未全部落库，不构成 QA-TS-002 验收证据："
				+ "succeeded=" + summary.succeededCount() + " failed=" + summary.failedCount()
				+ " outcome=" + summary.outcome());
		assertTrue(complete, "股票、ETF 和基金必须各自成功落库真实行情。");
	}

	private java.util.function.Predicate<Map<String, Object>> isType(String instrumentType, String priceType) {
		return price -> instrumentType.equals(String.valueOf(price.get("instrument_type")))
			&& priceType.equals(String.valueOf(price.get("price_type")));
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
