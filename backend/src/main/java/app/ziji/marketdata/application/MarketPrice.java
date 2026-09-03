package app.ziji.marketdata.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 投资模块使用的价格查询结果；价格始终为正，缺价由公开 port 的 Optional 表达。 */
public record MarketPrice(
	UUID instrumentId,
	PriceType priceType,
	BigDecimal price,
	String currency,
	LocalDate businessDate,
	MarketDataSource source,
	int revision,
	Instant sourceUpdatedAt,
	Instant fetchedAt,
	Freshness freshness) {

	public MarketPrice {
		if (instrumentId == null || priceType == null || price == null || price.signum() <= 0
			|| currency == null || businessDate == null || source == null || revision < 1
			|| fetchedAt == null || freshness == null) {
			throw new IllegalArgumentException("市场价格结果不完整。");
		}
	}
}
