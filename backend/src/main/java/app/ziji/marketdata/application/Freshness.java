package app.ziji.marketdata.application;

/** 估值质量；缺价由 Optional.empty() 表达，不以数值零代替。 */
public enum Freshness {
	FRESH,
	STALE,
	UNAVAILABLE
}
