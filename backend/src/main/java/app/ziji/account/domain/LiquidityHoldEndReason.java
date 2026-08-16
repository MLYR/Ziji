package app.ziji.account.domain;

/** 已结束占用的不可逆生命周期原因。 */
public enum LiquidityHoldEndReason {
	RELEASED,
	SUPERSEDED,
	EXPIRED
}
