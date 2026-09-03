package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 投资对公开市场数据的最小读取 seam；不允许跨模块查询 marketdata 表。 */
public interface InvestmentMarketDataPort {

	Optional<PriceSnapshot> latestPrice(UUID instrumentId, LocalDate businessDate, String expectedCurrency);

	/** 价格质量由适配器保留，应用层据此区分完整、过期和待数据状态。 */
	record PriceSnapshot(
		BigDecimal price,
		LocalDate businessDate,
		String currency,
		String source,
		Instant fetchedAt,
		String priceType,
		Freshness freshness) {

		public PriceSnapshot {
			if (price == null || price.signum() <= 0 || businessDate == null || currency == null
				|| source == null || fetchedAt == null || priceType == null || freshness == null) {
				throw new InvestmentRequestValidationException("价格快照不完整。");
			}
		}

		public PriceSnapshot(
			BigDecimal price,
			LocalDate businessDate,
			String currency,
			String source,
			Instant fetchedAt,
			String priceType) {
			this(price, businessDate, currency, source, fetchedAt, priceType, Freshness.FRESH);
		}

		public PriceSnapshot(BigDecimal price, LocalDate businessDate, String currency) {
			this(price, businessDate, currency, "UNKNOWN", Instant.EPOCH, "CLOSE", Freshness.FRESH);
		}
	}

	enum Freshness {
		FRESH,
		STALE,
		PENDING,
		UNAVAILABLE
	}
}
