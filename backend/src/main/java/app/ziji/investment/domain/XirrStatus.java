package app.ziji.investment.domain;

/** XIRR 的可用性和明确失败语义。 */
public enum XirrStatus {
	AVAILABLE,
	INSUFFICIENT_CASH_FLOWS,
	INVALID_CASH_FLOWS,
	NON_CONVERGENT
}
