package app.ziji.marketdata.application;

/** 数据库失败统一收口；原始 SQL、参数和供应商响应不进入 API 错误。 */
public final class MarketDataPersistenceException extends MarketDataApplicationException {

	public MarketDataPersistenceException(Throwable cause) {
		super("市场数据持久化失败。", cause);
	}
}
