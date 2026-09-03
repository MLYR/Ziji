package app.ziji.marketdata.application.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import app.ziji.marketdata.domain.PriceType;

/** 外部价格经适配层转换后的内部载荷，不包含原始供应商字段。 */
public record SourcePrice(
	PriceType priceType,
	LocalDate businessDate,
	BigDecimal price,
	String currency,
	Instant sourceUpdatedAt,
	String rawPayloadHash) {

	public SourcePrice {
		if (priceType == null || businessDate == null || price == null || price.signum() <= 0
			|| currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("外部价格转换结果无效。");
		}
	}
}
