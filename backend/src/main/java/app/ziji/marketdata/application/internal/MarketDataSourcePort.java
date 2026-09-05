package app.ziji.marketdata.application.internal;

import java.time.LocalDate;

import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;

/** 外部行情来源的内部适配边界；调用方只接收安全 outcome 和已转换数据。 */
public interface MarketDataSourcePort {

	SourceResult fetchPrices(
		Instrument instrument,
		InstrumentSourceMapping mapping,
		LocalDate from,
		LocalDate to);

	/** 远程产品基础信息搜索；无匹配、无 token 或供应商失败时返回空列表，不抛供应商异常。 */
	java.util.List<RemoteInstrument> searchBasics(String query);
}
