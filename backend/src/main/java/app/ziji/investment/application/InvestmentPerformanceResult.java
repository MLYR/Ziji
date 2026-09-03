package app.ziji.investment.application;

import java.math.BigDecimal;

import app.ziji.investment.domain.XirrStatus;

/** 收益结果；累计收益、收益率和年化值供应用消费者使用，HTTP 映射遵循现有契约字段。 */
public record InvestmentPerformanceResult(
	String currency,
	BigDecimal realizedProfit,
	BigDecimal unrealizedProfit,
	BigDecimal dividends,
	BigDecimal fees,
	BigDecimal taxes,
	BigDecimal cumulativeProfit,
	BigDecimal returnRate,
	BigDecimal annualizedReturn,
	BigDecimal xirr,
	XirrStatus xirrStatus) {
}
