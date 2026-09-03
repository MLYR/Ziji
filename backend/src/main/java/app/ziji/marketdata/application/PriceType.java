package app.ziji.marketdata.application;

/** 投资估值可请求的价格类型；该类型属于 application port 契约。 */
public enum PriceType {
	CLOSE,
	UNIT_NAV,
	ADJUSTED_CLOSE,
	MANUAL
}
