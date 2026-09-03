package app.ziji.marketdata.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 投资模块唯一的市场数据只读边界。实现和调用方均不得越过该接口访问 marketdata 的表、JDBC 或 jOOQ。
 */
@org.springframework.modulith.NamedInterface("application")
public interface MarketDataReadPort {

	/** 按内部不可变产品 ID 读取最小产品引用；不存在时返回 empty。 */
	Optional<InstrumentReference> findInstrument(UUID instrumentId);

	/** 按产品类型选择估值类型，并在同一类型内优先返回手工当前价格；无有效价格时返回 empty。 */
	Optional<MarketPrice> findLatestValuation(UUID instrumentId, LocalDate asOf);

	/** 按明确价格类型读取 asOf 当日及之前的最新当前修订；无价格时返回 empty。 */
	Optional<MarketPrice> findPrice(UUID instrumentId, PriceType priceType, LocalDate asOf);
}
