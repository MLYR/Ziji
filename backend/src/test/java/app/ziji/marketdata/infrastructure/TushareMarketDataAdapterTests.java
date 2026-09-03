package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

/** T-INV-002/003/005：Tushare 响应只转换为内部价格，失败不伪造价格并遵守重试边界。 */
class TushareMarketDataAdapterTests {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
	private static final Instrument STOCK = instrument(InstrumentType.STOCK, "A 股测试");
	private static final Instrument ETF = instrument(InstrumentType.ETF, "场内 ETF 测试");
	private static final Instrument FUND = instrument(InstrumentType.FUND, "公募基金测试");

	@Test
	void mapsDailyStockResponseToClosePriceWithoutLeakingSupplierFields() {
		FakeTransport transport = new FakeTransport(new TushareTransportResponse(200, """
			{"code":0,"msg":"","data":{"fields":["ts_code","trade_date","close"],"items":[
			["000001.SZ","20260901","10.50"],["000001.SZ","20260902","10.75"]]}}
			"""));
		TushareMarketDataAdapter adapter = adapter(transport, "server-secret", 0);

		var result = adapter.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals(1, result.attempts());
		assertEquals(2, result.prices().size());
		assertEquals("CLOSE", result.prices().getFirst().priceType().name());
		assertEquals(LocalDate.of(2026, 9, 1), result.prices().getFirst().businessDate());
		assertEquals(0, new BigDecimal("10.50").compareTo(result.prices().getFirst().price()));
		assertTrue(transport.bodies.getFirst().contains("\"api_name\":\"daily\""));
		assertTrue(transport.bodies.getFirst().contains("server-secret"));
		assertFalse(result.prices().getFirst().rawPayloadHash().isBlank());
	}

	@Test
	void mapsFundResponseToUnitNav() {
		FakeTransport transport = new FakeTransport(new TushareTransportResponse(200, """
			{"code":0,"data":{"fields":["ts_code","ann_date","nav_date","unit_nav"],"items":[
			["000001.OF","20260902","20260901","1.2345"]]}}
			"""));

		var result = adapter(transport, "server-secret", 0).fetchPrices(
			FUND, mapping(FUND, "000001.OF"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals("UNIT_NAV", result.prices().getFirst().priceType().name());
		assertEquals(LocalDate.of(2026, 9, 1), result.prices().getFirst().businessDate());
		assertEquals(0, new BigDecimal("1.2345").compareTo(result.prices().getFirst().price()));
		assertTrue(transport.bodies.getFirst().contains("\"api_name\":\"fund_nav\""));
	}

	@Test
	void mapsEtfResponseToClosePriceAndAcceptsNumericSupplierNodes() {
		FakeTransport transport = new FakeTransport(new TushareTransportResponse(200, """
			{"code":0,"data":{"fields":["ts_code","trade_date","close"],"items":[
			["510300.SH",20260901,4.125]]}}
			"""));

		var result = adapter(transport, "server-secret", 0).fetchPrices(
			ETF, mapping(ETF, "510300.SH"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

		assertEquals(SourceOutcome.SUCCESS, result.outcome());
		assertEquals("CLOSE", result.prices().getFirst().priceType().name());
		assertEquals(LocalDate.of(2026, 9, 1), result.prices().getFirst().businessDate());
		assertEquals(0, new BigDecimal("4.125").compareTo(result.prices().getFirst().price()));
		assertTrue(transport.bodies.getFirst().contains("\"api_name\":\"fund_daily\""));
	}

	@Test
	void retriesTransientFailureButReturnsNoTokenWithoutCallingTransport() {
		FakeTransport retrying = new FakeTransport(
			new TushareTransportResponse(503, "unavailable"),
			new TushareTransportResponse(200, """
				{"code":0,"data":{"fields":["ts_code","trade_date","close"],"items":[["000001.SZ","20260901","10"]]}}
				"""));
		List<Duration> waits = new ArrayList<>();
		TushareMarketDataAdapter adapter = new TushareMarketDataAdapter(
			retrying, new ObjectMapper(), "https://example.test", "server-secret", Duration.ofSeconds(1), 1,
			new TushareRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), Clock.fixed(NOW, ZoneOffset.UTC), waits::add);

		var retried = adapter.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
		assertEquals(SourceOutcome.SUCCESS, retried.outcome());
		assertEquals(2, retried.attempts());
		assertEquals(List.of(Duration.ofMillis(100)), waits);

		FakeTransport noTokenTransport = new FakeTransport();
		var noToken = new TushareMarketDataAdapter(
			noTokenTransport, new ObjectMapper(), "https://example.test", "", Duration.ofSeconds(1), 2,
			new TushareRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), Clock.fixed(NOW, ZoneOffset.UTC), waits::add)
			.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
		assertEquals(SourceOutcome.NO_TOKEN, noToken.outcome());
		assertEquals(0, noToken.attempts());
		assertTrue(noTokenTransport.bodies.isEmpty());
	}

	@Test
	void classifiesUnauthorizedAndRateLimitedResponsesWithoutPrices() {
		FakeTransport transport = new FakeTransport(
			new TushareTransportResponse(403, "forbidden"),
			new TushareTransportResponse(429, "rate limited"));
		TushareMarketDataAdapter adapter = adapter(transport, "server-secret", 0);

		var unauthorized = adapter.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
		var rateLimited = adapter.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

		assertEquals(SourceOutcome.UNAUTHORIZED, unauthorized.outcome());
		assertTrue(unauthorized.prices().isEmpty());
		assertEquals(SourceOutcome.RATE_LIMITED, rateLimited.outcome());
		assertTrue(rateLimited.prices().isEmpty());
	}

	@Test
	void interruptedRetryWaitStopsBeforeSendingAnotherSupplierRequest() {
		FakeTransport transport = new FakeTransport(new TushareTransportResponse(503, "unavailable"));
		TushareMarketDataAdapter adapter = new TushareMarketDataAdapter(
			transport, new ObjectMapper(), "https://example.test", "server-secret", Duration.ofSeconds(1), 2,
			new TushareRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), Clock.fixed(NOW, ZoneOffset.UTC), ignored -> {
				Thread.currentThread().interrupt();
			});

		try {
			var result = adapter.fetchPrices(STOCK, mapping("000001.SZ"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

			assertEquals(SourceOutcome.TIMEOUT, result.outcome());
			assertEquals(1, result.attempts());
			assertEquals(1, transport.bodies.size());
		} finally {
			Thread.interrupted();
		}
	}

	private static TushareMarketDataAdapter adapter(FakeTransport transport, String token, int maxRetries) {
		return new TushareMarketDataAdapter(
			transport, new ObjectMapper(), "https://example.test", token, Duration.ofSeconds(1), maxRetries,
			new TushareRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10), Clock.fixed(NOW, ZoneOffset.UTC), ignored -> {
			});
	}

	private static Instrument instrument(InstrumentType type, String name) {
		return new Instrument(UUID.randomUUID(), type, name, "CN", "CNY", InstrumentStatus.ACTIVE, NOW, NOW, 1);
	}

	private static InstrumentSourceMapping mapping(String externalCode) {
		return mapping(STOCK, externalCode);
	}

	private static InstrumentSourceMapping mapping(Instrument instrument, String externalCode) {
		return new InstrumentSourceMapping(UUID.randomUUID(), instrument.id(), PriceSource.TUSHARE, externalCode, "CN", null, null);
	}

	private static final class FakeTransport implements TushareTransport {
		private final Deque<Object> responses = new ArrayDeque<>();
		private final List<String> bodies = new ArrayList<>();

		private FakeTransport(Object... responses) {
			this.responses.addAll(List.of(responses));
		}

		@Override
		public TushareTransportResponse post(String endpoint, String body, Duration timeout)
			throws IOException, InterruptedException {
			bodies.add(body);
			Object response = responses.removeFirst();
			if (response instanceof IOException exception) {
				throw exception;
			}
			if (response instanceof InterruptedException exception) {
				throw exception;
			}
			return (TushareTransportResponse) response;
		}
	}
}
