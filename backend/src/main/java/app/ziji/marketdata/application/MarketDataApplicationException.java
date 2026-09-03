package app.ziji.marketdata.application;

/** 市场数据用例的稳定异常基类；不把 JDBC 或供应商异常泄漏到 HTTP 层。 */
public class MarketDataApplicationException extends RuntimeException {

	public MarketDataApplicationException(String message) {
		super(message);
	}

	public MarketDataApplicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
