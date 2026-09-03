package app.ziji.marketdata.application.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.PriceSnapshot;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;

/** marketdata 用例的内部写入端口；HTTP 和其他模块不能借此访问原始数据库。 */
public interface MarketDataCommandStore extends MarketDataReadStore {

	List<Instrument> search(String query, int limit);

	List<InstrumentSourceMapping> listMappings(UUID instrumentId);

	Instrument insertInstrument(Instrument instrument);

	List<PriceSnapshot> listCurrentPrices(UUID instrumentId, LocalDate from, LocalDate to, int limit);

	Optional<PriceSnapshot> findPriceById(UUID priceId);

	PriceSnapshot insertPrice(
		UUID id,
		UUID instrumentId,
		PriceSource source,
		PriceType priceType,
		LocalDate businessDate,
		BigDecimal price,
		String currency,
		Instant sourceUpdatedAt,
		Instant fetchedAt,
		UUID createdBy,
		String reason,
		String rawPayloadHash,
		UUID supersedesId,
		String contentHash);

	MarketDataStatus status(Instant now);

	record MarketDataStatus(String status, Instant lastSuccessfulSyncAt, String freshness) {
	}
}
