package app.ziji.marketdata.application.internal;

/** 远程产品搜索经适配层转换后的最小候选；不包含原始供应商字段。 */
public record RemoteInstrument(
	String externalCode,
	String name,
	String sourceMarket,
	String instrumentType) {

	public RemoteInstrument {
		if (externalCode == null || externalCode.isBlank() || name == null || name.isBlank()
			|| instrumentType == null || instrumentType.isBlank()) {
			throw new IllegalArgumentException("远程产品候选无效。");
		}
	}
}
