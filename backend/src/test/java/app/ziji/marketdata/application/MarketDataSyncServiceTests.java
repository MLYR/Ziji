package app.ziji.marketdata.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 增量同步用例的窗口选择、批量停止、幂等写入和运行记录行为。 */
class MarketDataSyncServiceTests {

	private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);
	private static final UUID RUN_ID = UUID.randomUUID();

	private final MarketDataCommandStore store = mock(MarketDataCommandStore.class);
	private final MarketDataSyncStore syncStore = mock(MarketDataSyncStore.class);
	private final MarketDataSourcePort source = mock(MarketDataSourcePort.class);
	private final TransactionRunner transactions = new InlineTransactionRunner();
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final MarketDataSyncService service = new MarketDataSyncService(store, syncStore, source, transactions, clock);

	@BeforeEach
	void setUp() {
		when(syncStore.tryAcquireSyncLock()).thenReturn(true);
		when(syncStore.beginSyncRun(any(Instant.class), anyInt())).thenReturn(RUN_ID);
	}

	@Test
	void skipsRunWhenAnotherInstanceHoldsTheSchedulingLock() {
		when(syncStore.tryAcquireSyncLock()).thenReturn(false);

		var summary = service.syncIncremental();

		assertEquals("SKIPPED", summary.status());
		assertEquals("SKIPPED_LOCK_HELD", summary.outcome());
		verify(syncStore, never()).beginSyncRun(any(), any(int.class));
		verify(syncStore, never()).listSyncCandidates();
	}

	@Test
	void recordsEmptyRunWhenNoCandidatesExist() {
		when(syncStore.listSyncCandidates()).thenReturn(List.of());

		var summary = service.syncIncremental();

		assertEquals("SUCCEEDED", summary.status());
		assertEquals("NO_CANDIDATES", summary.outcome());
		verify(syncStore).completeSyncRun(eq(RUN_ID), any(Instant.class), any(String.class),
			any(int.class), any(int.class), any(String.class), any());
		verify(source, never()).fetchPrices(any(), any(), any(), any());
	}

	@Test
	void fetchesFromDayAfterLastCloseAndPersistsWithoutDuplicateWrites() {
		var candidate = candidate(InstrumentType.STOCK, Optional.of(LocalDate.of(2026, 9, 1)), Optional.empty());
		Instrument instrument = instrument(candidate.instrumentId(), InstrumentType.STOCK);
		var mapping = mapping(candidate.mappingId(), candidate.instrumentId());
		when(syncStore.listSyncCandidates()).thenReturn(List.of(candidate));
		when(store.findInstrument(candidate.instrumentId())).thenReturn(Optional.of(instrument));
		when(store.listMappings(candidate.instrumentId())).thenReturn(List.of(mapping));
		SourcePrice price = new SourcePrice(PriceType.CLOSE, LocalDate.of(2026, 9, 2), new BigDecimal("10.50"),
			"CNY", NOW, "payload-hash");
		when(source.fetchPrices(instrument, mapping, LocalDate.of(2026, 9, 2), TODAY))
			.thenReturn(new SourceResult(SourceOutcome.SUCCESS, List.of(price), 1, NOW));

		var summary = service.syncIncremental();

		assertEquals("SUCCEEDED", summary.status());
		assertEquals(1, summary.succeededCount());
		assertEquals("SUCCESS", summary.outcome());
		verify(source).fetchPrices(instrument, mapping, LocalDate.of(2026, 9, 2), TODAY);
		verify(store).insertPrice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(syncStore).touchMappingLastSyncedAt(eq(candidate.mappingId()), eq(NOW));
	}

	@Test
	void skipsFetchWhenInstrumentAlreadyHasTodaysPrice() {
		var candidate = candidate(InstrumentType.STOCK, Optional.of(TODAY), Optional.empty());
		when(syncStore.listSyncCandidates()).thenReturn(List.of(candidate));

		var summary = service.syncIncremental();

		assertEquals("SUCCEEDED", summary.status());
		assertEquals(1, summary.succeededCount());
		verify(source, never()).fetchPrices(any(), any(), any(), any());
	}

	@Test
	void stopsBatchOnNoTokenAndRecordsFailedOutcome() {
		var stock = candidate(InstrumentType.STOCK, Optional.empty(), Optional.empty());
		var fund = candidate(InstrumentType.FUND, Optional.empty(), Optional.empty());
		Instrument stockInstrument = instrument(stock.instrumentId(), InstrumentType.STOCK);
		Instrument fundInstrument = instrument(fund.instrumentId(), InstrumentType.FUND);
		var stockMapping = mapping(stock.mappingId(), stock.instrumentId());
		var fundMapping = mapping(fund.mappingId(), fund.instrumentId());
		when(syncStore.listSyncCandidates()).thenReturn(List.of(stock, fund));
		when(store.findInstrument(stock.instrumentId())).thenReturn(Optional.of(stockInstrument));
		when(store.listMappings(stock.instrumentId())).thenReturn(List.of(stockMapping));
		when(source.fetchPrices(any(), any(), any(), any()))
			.thenReturn(SourceResult.failure(SourceOutcome.NO_TOKEN, 0, NOW));

		var summary = service.syncIncremental();

		assertEquals("FAILED", summary.status());
		assertEquals("NO_TOKEN", summary.outcome());
		assertEquals(1, summary.failedCount());
		verify(source, times(1)).fetchPrices(any(), any(), any(), any());
		verify(store, never()).findInstrument(fund.instrumentId());
	}

	@Test
	void continuesAfterProviderFailureAndMarksPartialRun() {
		var first = candidate(InstrumentType.STOCK, Optional.empty(), Optional.empty());
		var second = candidate(InstrumentType.FUND, Optional.empty(), Optional.empty());
		when(syncStore.listSyncCandidates()).thenReturn(List.of(first, second));
		Instrument firstInstrument = instrument(first.instrumentId(), InstrumentType.STOCK);
		Instrument secondInstrument = instrument(second.instrumentId(), InstrumentType.FUND);
		var firstMapping = mapping(first.mappingId(), first.instrumentId());
		var secondMapping = mapping(second.mappingId(), second.instrumentId());
		when(store.findInstrument(first.instrumentId())).thenReturn(Optional.of(firstInstrument));
		when(store.findInstrument(second.instrumentId())).thenReturn(Optional.of(secondInstrument));
		when(store.listMappings(first.instrumentId())).thenReturn(List.of(firstMapping));
		when(store.listMappings(second.instrumentId())).thenReturn(List.of(secondMapping));
		when(source.fetchPrices(any(), any(), any(), any()))
			.thenReturn(
				SourceResult.failure(SourceOutcome.UNAVAILABLE, 2, NOW),
				new SourceResult(SourceOutcome.NO_DATA, List.of(), 1, NOW));

		var summary = service.syncIncremental();

		assertEquals("PARTIAL", summary.status());
		assertEquals(1, summary.succeededCount());
		assertEquals(1, summary.failedCount());
		assertEquals("PROVIDER_UNAVAILABLE", summary.outcome());
		verify(syncStore).touchMappingLastSyncedAt(eq(second.mappingId()), eq(NOW));
	}

	@Test
	void usesLookbackWindowWhenNoHistoryExists() {
		var candidate = candidate(InstrumentType.FUND, Optional.empty(), Optional.empty());
		Instrument instrument = instrument(candidate.instrumentId(), InstrumentType.FUND);
		var mapping = mapping(candidate.mappingId(), candidate.instrumentId());
		when(syncStore.listSyncCandidates()).thenReturn(List.of(candidate));
		when(store.findInstrument(candidate.instrumentId())).thenReturn(Optional.of(instrument));
		when(store.listMappings(candidate.instrumentId())).thenReturn(List.of(mapping));
		when(source.fetchPrices(any(), any(), any(), any()))
			.thenReturn(new SourceResult(SourceOutcome.NO_DATA, List.of(), 1, NOW));

		service.syncIncremental();

		verify(source).fetchPrices(instrument, mapping, LocalDate.of(2026, 8, 4), TODAY);
	}

	private static MarketDataSyncStore.SyncCandidate candidate(
		InstrumentType type, Optional<LocalDate> lastClose, Optional<LocalDate> lastNav) {
		return new MarketDataSyncStore.SyncCandidate(
			UUID.randomUUID(), type, "CNY", UUID.randomUUID(), "000001.SZ", lastClose, lastNav, Optional.empty());
	}

	private static Instrument instrument(UUID id, InstrumentType type) {
		return new Instrument(id, type, "测试产品", "CN", "CNY", InstrumentStatus.ACTIVE, NOW, NOW, 1);
	}

	private static InstrumentSourceMapping mapping(UUID mappingId, UUID instrumentId) {
		return new InstrumentSourceMapping(mappingId, instrumentId, PriceSource.THS, "000001.SZ", "CN", null, null);
	}

	private static final class InlineTransactionRunner implements TransactionRunner {

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
