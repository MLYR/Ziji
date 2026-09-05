package app.ziji.marketdata.infrastructure;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHG-MD-001：同花顺 K 线/基金净值响应只转换为内部价格，失败不伪造价格并遵守重试边界。 */
class ThsMarketDataAdapterTests {

	private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
	private static final Instrument STOCK = instrument(InstrumentType.STOCK, "A 股测试");
	private static final Instrument ETF = instrument(InstrumentType.ETF, "场内 ETF 测试");
	private static final Instrument FUND = instrument(InstrumentType.FUND, "公募基金测试");

	@Test
	void mapsKlineResponseToClosePricesWithinWindow() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, """
			quotebridge_v6_line_hs_000001_01_last1800({"name":"平安银行","today":"20260905","data":
			"20260901,11.68,11.96,11.65,11.92,152316450,1807254400.00,0.785;20260902,11.92,11.99,11.85,11.91,89224752,1063261810.00,0.460;20260903,11.88,12.08,11.83,11.88,110513439,1324230290.00,0.570"})
			"""));
		ThsMarketDataAdapter adapter = adapter(transport);

		var result = adapter.fetchPrices(STOCK, mapping("000001"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals(3, result.prices().size());
		assertEquals("CLOSE", result.prices().getFirst().priceType().name());
		assertEquals(LocalDate.of(2026, 9, 1), result.prices().getFirst().businessDate());
		assertEquals(0, new BigDecimal("11.92").compareTo(result.prices().getFirst().price()));
		assertTrue(result.prices().getFirst().rawPayloadHash().matches("[0-9a-f]{64}"));
		assertTrue(transport.urls.getFirst().contains("v6/line/hs_000001/01/last1800.js"));
	}

	@Test
	void mapsEtfKlineToClosePriceAndSkipsRowsOutsideWindow() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, """
			quotebridge_v6_line_hs_510300_01_last1800({"name":"沪深300ETF华泰柏瑞","today":"20260905","data":
			"20260831,4.60,4.70,4.50,4.65,1,1.00,0.0;20260901,4.683,4.705,4.666,4.684,846798310,3962899800.000,3.682;20260905,4.637,4.672,4.599,4.616,841465540,3905673000.000,3.600"})
			"""));
		ThsMarketDataAdapter adapter = adapter(transport);

		var result = adapter.fetchPrices(ETF, mapping(ETF, "510300"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals(1, result.prices().size());
		assertEquals(LocalDate.of(2026, 9, 1), result.prices().getFirst().businessDate());
		assertEquals(0, new BigDecimal("4.684").compareTo(result.prices().getFirst().price()));
	}

	@Test
	void mapsFundNavResponseToUnitNav() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, """
			var dwjz_000001=[["20260901","1.2700"],["20260902","1.2790"],["20260904","1.2390"]]
			"""));
		ThsMarketDataAdapter adapter = adapter(transport);

		var result = adapter.fetchPrices(FUND, mapping(FUND, "000001"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals(3, result.prices().size());
		assertEquals("UNIT_NAV", result.prices().getFirst().priceType().name());
		assertEquals(0, new BigDecimal("1.2700").compareTo(result.prices().getFirst().price()));
		assertTrue(transport.urls.getFirst().contains("fund.10jqka.com.cn/000001/json/jsondwjz.json"));
	}

	@Test
	void retriesTransientFailureAndStopsOnInterruptedWait() {
		FakeTransport retrying = new FakeTransport(
			new ThsTransportResponse(503, "unavailable"),
			new ThsTransportResponse(200, """
				quotebridge_v6_line_hs_000001_01_last1800({"name":"平安银行","data":"20260901,11.68,11.96,11.65,11.92,1,1.00,0.0"})
				"""));
		List<Duration> waits = new java.util.ArrayList<>();
		ThsMarketDataAdapter adapter = new ThsMarketDataAdapter(
			retrying, new ObjectMapper(), Duration.ofSeconds(1), 1,
			new ThsRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), usageDate -> true,
			Clock.fixed(NOW, ZoneOffset.UTC), waits::add);

		var retried = adapter.fetchPrices(STOCK, mapping("000001"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
		assertEquals(SourceOutcome.SUCCESS, retried.outcome());
		assertEquals(2, retried.attempts());
		assertEquals(List.of(Duration.ofMillis(100)), waits);
	}

	@Test
	void returnsNoDataWhenResponseCannotBeParsedAsPrices() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, "<html>404</html>"));
		ThsMarketDataAdapter adapter = adapter(transport);

		var result = adapter.fetchPrices(STOCK, mapping("000001"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

		assertEquals(SourceOutcome.NO_DATA, result.outcome());
		assertTrue(result.prices().isEmpty());
	}

	@Test
	void quotaExhaustedReturnsRateLimitedWithoutCallingTransport() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, ""));
		ThsMarketDataAdapter adapter = new ThsMarketDataAdapter(
			transport, new ObjectMapper(), Duration.ofSeconds(1), 0,
			new ThsRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), usageDate -> false,
			Clock.fixed(NOW, ZoneOffset.UTC), ignored -> {
			});

		var result = adapter.fetchPrices(STOCK, mapping("000001"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

		assertEquals(SourceOutcome.RATE_LIMITED, result.outcome());
		assertTrue(result.prices().isEmpty());
		assertTrue(transport.urls.isEmpty());
	}

	@Test
	void rejectsNonThsMappingsAndOtherInstrumentTypesWithoutCallingTransport() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, "x"));
		ThsMarketDataAdapter adapter = adapter(transport);

		var wrongSource = adapter.fetchPrices(STOCK, new InstrumentSourceMapping(
			UUID.randomUUID(), STOCK.id(), PriceSource.MANUAL, "000001", "CN", null, null),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
		var otherType = adapter.fetchPrices(instrument(InstrumentType.OTHER, "其他"), mapping("000001"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

		assertEquals(SourceOutcome.ERROR, wrongSource.outcome());
		assertEquals(SourceOutcome.ERROR, otherType.outcome());
		assertTrue(transport.urls.isEmpty());
	}

	@Test
	void remoteSearchIsUnavailableAndReturnsEmpty() {
		FakeTransport transport = new FakeTransport(new ThsTransportResponse(200, "x"));

		assertTrue(adapter(transport).searchBasics("平安").isEmpty());
		assertTrue(transport.urls.isEmpty(), "同花顺无公开搜索接口，不得发起任何请求。");
	}

	private static ThsMarketDataAdapter adapter(FakeTransport transport) {
		return new ThsMarketDataAdapter(
			transport, new ObjectMapper(), Duration.ofSeconds(1), 0,
			new ThsRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), usageDate -> true,
			Clock.fixed(NOW, ZoneOffset.UTC), ignored -> {
			});
	}

	private static Instrument instrument(InstrumentType type, String name) {
		return new Instrument(UUID.randomUUID(), type, name, "CN", "CNY", InstrumentStatus.ACTIVE, NOW, NOW, 1);
	}

	private static InstrumentSourceMapping mapping(String externalCode) {
		return mapping(STOCK, externalCode);
	}

	private static InstrumentSourceMapping mapping(Instrument instrument, String externalCode) {
		return new InstrumentSourceMapping(UUID.randomUUID(), instrument.id(), PriceSource.THS, externalCode, "CN", null, null);
	}

	private static final class FakeTransport implements ThsTransport {
		private final java.util.Deque<ThsTransportResponse> responses = new java.util.ArrayDeque<>();
		private final List<String> urls = new java.util.ArrayList<>();

		private FakeTransport(ThsTransportResponse... responses) {
			this.responses.addAll(List.of(responses));
		}

		@Override
		public ThsTransportResponse get(String url, Duration timeout) {
			urls.add(url);
			return responses.removeFirst();
		}
	}
}
