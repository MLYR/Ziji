package app.ziji.marketdata.application;

import java.util.UUID;

/** 投资模块可使用的最小产品引用，不暴露供应商原始字段或映射详情。 */
public record InstrumentReference(
	UUID id,
	String instrumentType,
	String name,
	String market,
	String currency,
	String status,
	int version) {

	public InstrumentReference {
		if (id == null || instrumentType == null || name == null || market == null || currency == null
			|| status == null || version < 1) {
			throw new IllegalArgumentException("产品引用不完整。");
		}
	}
}
