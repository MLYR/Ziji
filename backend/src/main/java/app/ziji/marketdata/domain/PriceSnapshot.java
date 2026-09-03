package app.ziji.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** price_snapshots 表内部事实；修订链字段保留在内部，API/application port 不暴露。 */
public record PriceSnapshot(
	UUID id,
	UUID instrumentId,
	PriceSource source,
	PriceType priceType,
	LocalDate businessDate,
	BigDecimal price,
	String currency,
	Instant sourceUpdatedAt,
	Instant fetchedAt,
	int revisionNo,
	boolean current,
	UUID supersedesId,
	UUID createdBy,
	String reason,
	String rawPayloadHash,
	String contentHash) {

	private static final Set<String> CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	public PriceSnapshot {
		if (id == null || instrumentId == null || source == null || priceType == null || businessDate == null
			|| price == null || price.signum() <= 0 || currency == null || !CURRENCIES.contains(currency)
			|| fetchedAt == null || revisionNo < 1 || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("价格快照事实无效。");
		}
	}
}
