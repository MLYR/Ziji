package app.ziji.marketdata.application;

/** 产品或价格不存在时使用统一的非枚举错误。 */
public final class MarketDataNotFoundException extends MarketDataApplicationException {

	public MarketDataNotFoundException() {
		super("市场数据资源不存在。");
	}
}
