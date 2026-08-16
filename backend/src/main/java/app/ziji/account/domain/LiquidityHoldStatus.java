package app.ziji.account.domain;

/** 由生命周期事实和查询时点推导的公共状态，不新增持久化 status 列。 */
public enum LiquidityHoldStatus {
	PENDING,
	ACTIVE,
	RELEASED,
	SUPERSEDED,
	EXPIRED
}
