package app.ziji.account.domain;

/** 流动性占用事实来源；公共人工路径只能写入 MANUAL。 */
public enum LiquidityHoldSource {
	MANUAL,
	IMPORT,
	SYSTEM
}
