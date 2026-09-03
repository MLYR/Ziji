package app.ziji.investment.domain;

/** 投资收益日的完整性状态。 */
public enum ReturnStatus {
	CALCULATED,
	NON_TRADING_DAY,
	NO_POSITION,
	PENDING_DATA,
	PARTIAL,
	UNPRICED
}
