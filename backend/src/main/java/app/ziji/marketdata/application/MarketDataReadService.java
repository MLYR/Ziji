package app.ziji.marketdata.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import app.ziji.marketdata.application.internal.MarketDataReadStore;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSnapshot;
import app.ziji.marketdata.domain.PriceSource;

/** marketdata.application 的稳定只读实现；产品和价格事实仍由内部 store 读取。 */
public final class MarketDataReadService implements MarketDataReadPort {

	private static final Duration DEFAULT_FRESHNESS_WINDOW = Duration.ofDays(3);

	private final MarketDataReadStore store;
	private final Clock clock;
	private final Duration freshnessWindow;

	public MarketDataReadService(MarketDataReadStore store, Clock clock) {
		this(store, clock, DEFAULT_FRESHNESS_WINDOW);
	}

	public MarketDataReadService(MarketDataReadStore store, Clock clock, Duration freshnessWindow) {
		this.store = Objects.requireNonNull(store, "市场数据读取适配器不能为空。");
		this.clock = Objects.requireNonNull(clock, "市场数据时钟不能为空。");
		if (freshnessWindow == null || freshnessWindow.isNegative() || freshnessWindow.isZero()) {
			throw new IllegalArgumentException("市场数据新鲜度窗口无效。");
		}
		this.freshnessWindow = freshnessWindow;
	}

	@Override
	public Optional<InstrumentReference> findInstrument(java.util.UUID instrumentId) {
		if (instrumentId == null) {
			return Optional.empty();
		}
		return store.findInstrument(instrumentId).map(MarketDataReadService::reference);
	}

	@Override
	public Optional<MarketPrice> findLatestValuation(java.util.UUID instrumentId, LocalDate asOf) {
		if (instrumentId == null || asOf == null) {
			return Optional.empty();
		}
		Optional<Instrument> instrument = store.findInstrument(instrumentId);
		if (instrument.isEmpty()) {
			return Optional.empty();
		}
		for (app.ziji.marketdata.domain.PriceType priceType : valuationTypes(instrument.get().instrumentType())) {
			Optional<MarketPrice> manual = latest(instrumentId, PriceSource.MANUAL, priceType, asOf);
			if (manual.isPresent()) {
				return manual;
			}
			Optional<MarketPrice> external = latest(instrumentId, PriceSource.THS, priceType, asOf);
			if (external.isPresent()) {
				return external;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<MarketPrice> findPrice(
		java.util.UUID instrumentId,
		PriceType priceType,
		LocalDate asOf) {
		if (instrumentId == null || priceType == null || asOf == null) {
			return Optional.empty();
		}
		app.ziji.marketdata.domain.PriceType domainType = app.ziji.marketdata.domain.PriceType.valueOf(priceType.name());
		return latest(instrumentId, PriceSource.MANUAL, domainType, asOf)
			.or(() -> latest(instrumentId, PriceSource.THS, domainType, asOf));
	}

	private Optional<MarketPrice> latest(
		java.util.UUID instrumentId,
		PriceSource source,
		app.ziji.marketdata.domain.PriceType priceType,
		LocalDate asOf) {
		return store.findLatestPrice(instrumentId, source, priceType, asOf).map(snapshot -> toMarketPrice(snapshot, asOf));
	}

	private MarketPrice toMarketPrice(PriceSnapshot snapshot, LocalDate asOf) {
		Instant cutoff = clock.instant().minus(freshnessWindow);
		boolean stale = snapshot.businessDate().isBefore(asOf.minusDays(freshnessWindow.toDays()))
			|| snapshot.fetchedAt().isBefore(cutoff);
		return new MarketPrice(
			snapshot.instrumentId(),
			PriceType.valueOf(snapshot.priceType().name()),
			snapshot.price(),
			snapshot.currency(),
			snapshot.businessDate(),
			MarketDataSource.valueOf(snapshot.source().name()),
			snapshot.revisionNo(),
			snapshot.sourceUpdatedAt(),
			snapshot.fetchedAt(),
			stale ? Freshness.STALE : Freshness.FRESH);
	}

	private static List<app.ziji.marketdata.domain.PriceType> valuationTypes(InstrumentType instrumentType) {
		return switch (instrumentType) {
			case STOCK, ETF -> List.of(
				app.ziji.marketdata.domain.PriceType.CLOSE,
				app.ziji.marketdata.domain.PriceType.MANUAL);
			case FUND -> List.of(
				app.ziji.marketdata.domain.PriceType.UNIT_NAV,
				app.ziji.marketdata.domain.PriceType.MANUAL);
			case OTHER -> List.of(
				app.ziji.marketdata.domain.PriceType.MANUAL,
				app.ziji.marketdata.domain.PriceType.CLOSE,
				app.ziji.marketdata.domain.PriceType.UNIT_NAV);
		};
	}

	private static InstrumentReference reference(Instrument instrument) {
		return new InstrumentReference(
			instrument.id(), instrument.instrumentType().name(), instrument.name(), instrument.market(),
			instrument.currency(), instrument.status().name(), instrument.version());
	}
}
