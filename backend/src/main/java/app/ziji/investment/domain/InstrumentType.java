package app.ziji.investment.domain;

/** V1 支持的投资标的类型，与 instruments 约束保持一致。 */
public enum InstrumentType {
	STOCK,
	FUND,
	ETF,
	OTHER
}
