package app.ziji.investment.domain;

/** 持仓估值状态；缺价时不得用零伪造市值。 */
public enum ValuationStatus {
	PRICED,
	UNPRICED
}
