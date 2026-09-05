package app.ziji.marketdata.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.RemoteInstrument;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-TS-001：本地缓存未命中时服务端按限流策略触发 Tushare 基础信息查询并缓存命中产品。 */
class MarketDataRemoteSearchTests {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
	private static final UUID USER = UUID.randomUUID();

	private final MarketDataCommandStore store = mock(MarketDataCommandStore.class);
	private final MarketDataSourcePort source = mock(MarketDataSourcePort.class);
	private final AuditLogWritePort auditLogs = mock(AuditLogWritePort.class);
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void localHitNeverTriggersRemoteSearch() {
		MarketDataApplicationService service = service(true);
		Instrument local = new Instrument(UUID.randomUUID(), InstrumentType.STOCK, "本地产品", "CN", "CNY",
			InstrumentStatus.ACTIVE, NOW, NOW, 1);
		when(store.search("平安", 10)).thenReturn(List.of(local));
		when(store.listMappings(local.id())).thenReturn(List.of());

		var result = service.search(USER, "平安", 10, "request-id");

		assertEquals(1, result.size());
		verify(source, never()).searchBasics(any());
	}

	@Test
	void localMissWithRemoteDisabledFallsBackToEmptyResult() {
		MarketDataApplicationService service = service(false);
		when(store.search("平安", 10)).thenReturn(List.of());

		var result = service.search(USER, "平安", 10, "request-id");

		assertTrue(result.isEmpty());
		verify(source, never()).searchBasics(any());
	}

	@Test
	void localMissTriggersRemoteSearchAndCachesMatchedInstrument() {
		MarketDataApplicationService service = service(true);
		when(store.search("平安", 10)).thenReturn(List.of());
		when(source.searchBasics("平安")).thenReturn(List.of(
			new RemoteInstrument("000001.SZ", "平安银行", "SZ", "STOCK")));
		when(store.findByExternalCode(PriceSource.THS, "000001.SZ")).thenReturn(Optional.empty());
		Instrument created = new Instrument(UUID.randomUUID(), InstrumentType.STOCK, "平安银行", "SZ", "CNY",
			InstrumentStatus.ACTIVE, NOW, NOW, 1);
		when(store.insertInstrumentWithMapping(any(Instrument.class), eq(PriceSource.THS), eq("000001.SZ"), eq("SZ")))
			.thenReturn(created);
		when(store.listMappings(created.id())).thenReturn(List.of());

		var result = service.search(USER, "平安", 10, "request-id");

		assertEquals(1, result.size());
		assertEquals("平安银行", result.getFirst().name());
		assertEquals("STOCK", result.getFirst().instrumentType());
		verify(store).insertInstrumentWithMapping(any(Instrument.class), eq(PriceSource.THS), eq("000001.SZ"), eq("SZ"));
		verify(auditLogs).append(any());
	}

	@Test
	void remoteCandidateAlreadyMappedReusesExistingInstrumentWithoutInsert() {
		MarketDataApplicationService service = service(true);
		when(store.search("平安", 10)).thenReturn(List.of());
		when(source.searchBasics("平安")).thenReturn(List.of(
			new RemoteInstrument("000001.SZ", "平安银行", "SZ", "STOCK")));
		Instrument existing = new Instrument(UUID.randomUUID(), InstrumentType.STOCK, "平安银行", "SZ", "CNY",
			InstrumentStatus.ACTIVE, NOW, NOW, 1);
		when(store.findByExternalCode(PriceSource.THS, "000001.SZ")).thenReturn(Optional.of(existing));
		when(store.listMappings(existing.id())).thenReturn(List.of(
			new InstrumentSourceMapping(UUID.randomUUID(), existing.id(), PriceSource.THS, "000001.SZ", "SZ", null, null)));

		var result = service.search(USER, "平安", 10, "request-id");

		assertEquals(1, result.size());
		verify(store, never()).insertInstrumentWithMapping(any(), any(), any(), any());
	}

	@Test
	void remoteSearchFailureFallsBackToEmptyResultInsteadOfError() {
		MarketDataApplicationService service = service(true);
		when(store.search("平安", 10)).thenReturn(List.of());
		when(source.searchBasics("平安")).thenThrow(new IllegalStateException("boom"));

		var result = service.search(USER, "平安", 10, "request-id");

		assertTrue(result.isEmpty());
	}

	private MarketDataApplicationService service(boolean remoteEnabled) {
		return new MarketDataApplicationService(store, new InlineRunner(), auditLogs, source, remoteEnabled, clock);
	}

	private static final class InlineRunner implements TransactionRunner {

		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}
}
