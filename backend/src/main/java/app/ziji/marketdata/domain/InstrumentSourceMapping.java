package app.ziji.marketdata.domain;

import java.time.Instant;
import java.util.UUID;

/** instrument_source_mappings 表内部模型；rawMetadata 不得越过 marketdata.application。 */
public record InstrumentSourceMapping(
	UUID id,
	UUID instrumentId,
	PriceSource source,
	String externalCode,
	String sourceMarket,
	String rawMetadata,
	Instant lastSyncedAt) {

	public InstrumentSourceMapping {
		if (id == null || instrumentId == null || source == null || externalCode == null
			|| externalCode.isBlank() || externalCode.length() > 80) {
			throw new IllegalArgumentException("产品来源映射无效。");
		}
	}
}
