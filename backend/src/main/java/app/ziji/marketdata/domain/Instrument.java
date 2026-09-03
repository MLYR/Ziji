package app.ziji.marketdata.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** instruments 表内部事实；供应商映射和原始 metadata 保持在独立模型。 */
public record Instrument(
	UUID id,
	InstrumentType instrumentType,
	String name,
	String market,
	String currency,
	InstrumentStatus status,
	Instant createdAt,
	Instant updatedAt,
	int version) {

	private static final Set<String> CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	public Instrument {
		if (id == null || instrumentType == null || name == null || name.isBlank() || name.length() > 200
			|| market == null || market.isBlank() || market.length() > 40 || currency == null
			|| !CURRENCIES.contains(currency) || status == null || createdAt == null || updatedAt == null
			|| version < 1) {
			throw new IllegalArgumentException("产品事实无效。");
		}
	}
}
