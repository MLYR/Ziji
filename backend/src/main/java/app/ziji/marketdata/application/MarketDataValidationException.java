package app.ziji.marketdata.application;

/** 市场数据请求参数或业务边界不合法。 */
public final class MarketDataValidationException extends MarketDataApplicationException {

	public MarketDataValidationException(String message) {
		super(message);
	}
}
