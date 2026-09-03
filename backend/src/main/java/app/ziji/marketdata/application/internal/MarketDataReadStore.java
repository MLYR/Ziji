package app.ziji.marketdata.application.internal;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.PriceSnapshot;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;

/** 市场数据读取适配器的内部端口；jOOQ/JDBC 类型不得离开 infrastructure。 */
public interface MarketDataReadStore {

	Optional<Instrument> findInstrument(UUID instrumentId);

	Optional<PriceSnapshot> findLatestPrice(
		UUID instrumentId,
		PriceSource source,
		PriceType priceType,
		LocalDate asOf);
}
