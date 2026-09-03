package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.investment.domain.ValuationStatus;

/** 持仓查询结果；UNPRICED 时估值字段保持 null。 */
public record InvestmentPositionResult(
	UUID instrumentId,
	BigDecimal quantity,
	BigDecimal costBasis,
	BigDecimal averageCost,
	ValuationStatus valuationStatus,
	BigDecimal marketPrice,
	BigDecimal marketValue,
	BigDecimal unrealizedProfit,
	LocalDate priceAsOf,
	String currency,
	InvestmentMarketDataPort.Freshness priceFreshness) {

	public InvestmentPositionResult(
		UUID instrumentId,
		BigDecimal quantity,
		BigDecimal costBasis,
		BigDecimal averageCost,
		ValuationStatus valuationStatus,
		BigDecimal marketPrice,
		BigDecimal marketValue,
		BigDecimal unrealizedProfit,
		LocalDate priceAsOf,
		String currency) {
		this(instrumentId, quantity, costBasis, averageCost, valuationStatus, marketPrice, marketValue, unrealizedProfit,
			priceAsOf, currency, null);
	}
}
