package app.ziji.marketdata.application;

/** 幂等记录仍在其他请求处理中，调用方可按 Retry-After 重试。 */
public final class MarketDataRetryableException extends MarketDataApplicationException {

	public MarketDataRetryableException() {
		super("请求仍在处理中，请稍后重试。");
	}
}
