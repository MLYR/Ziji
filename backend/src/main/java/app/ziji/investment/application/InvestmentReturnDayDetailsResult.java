package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import app.ziji.investment.domain.ReturnStatus;

/** 单日收益归因结果；不完整估值时收益字段统一为 null。 */
public record InvestmentReturnDayDetailsResult(
	String scopeType,
	UUID instrumentId,
	LocalDate businessDate,
	String baseCurrency,
	int valuationRevision,
	Instant asOf,
	ReturnStatus status,
	BigDecimal beginValue,
	BigDecimal endValue,
	BigDecimal netCashFlow,
	BigDecimal dailyProfit,
	BigDecimal dailyReturnRate,
	BigDecimal marketEffect,
	BigDecimal fxEffect,
	BigDecimal dividends,
	BigDecimal fees,
	BigDecimal taxes,
	List<Contribution> contributions,
	List<String> dataQualityWarnings) {

	public InvestmentReturnDayDetailsResult {
		contributions = List.copyOf(contributions == null ? List.of() : contributions);
		dataQualityWarnings = List.copyOf(dataQualityWarnings == null ? List.of() : dataQualityWarnings);
	}

	public record Contribution(
		String contributionType,
		UUID instrumentId,
		String label,
		BigDecimal profit,
		BigDecimal returnRate,
		ReturnStatus status,
		LocalDate priceAsOf) {
	}
}
