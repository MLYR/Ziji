package app.ziji.marketdata.application;

/** 同一资源的版本或幂等请求冲突。 */
public final class MarketDataConflictException extends MarketDataApplicationException {

	public MarketDataConflictException(String message) {
		super(message);
	}
}
