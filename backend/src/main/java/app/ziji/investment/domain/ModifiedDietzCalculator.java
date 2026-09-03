package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Modified Dietz 日收益计算；现金流权重按业务日内剩余时间计算。 */
public final class ModifiedDietzCalculator {

	public Result calculate(
		BigDecimal beginValue,
		BigDecimal endValue,
		Instant periodStart,
		Instant periodEnd,
		List<CashFlow> cashFlows) {
		if (beginValue == null || endValue == null || periodStart == null || periodEnd == null
			|| cashFlows == null || !periodEnd.isAfter(periodStart)) {
			throw new InvestmentDomainException("Modified Dietz 周期参数无效。");
		}
		BigDecimal duration = BigDecimal.valueOf(Duration.between(periodStart, periodEnd).toNanos());
		BigDecimal netCashFlow = BigDecimal.ZERO;
		BigDecimal weightedCashFlow = BigDecimal.ZERO;
		for (CashFlow cashFlow : cashFlows) {
			if (cashFlow.occurredAt().isBefore(periodStart) || cashFlow.occurredAt().isAfter(periodEnd)) {
				throw new InvestmentDomainException("Modified Dietz 现金流超出计算周期。");
			}
			BigDecimal remaining = BigDecimal.valueOf(Duration.between(cashFlow.occurredAt(), periodEnd).toNanos());
			BigDecimal weight = remaining.divide(duration, 24, java.math.RoundingMode.HALF_UP);
			netCashFlow = netCashFlow.add(cashFlow.amount());
			weightedCashFlow = weightedCashFlow.add(cashFlow.amount().multiply(weight));
		}
		return fromComponents(beginValue, endValue, netCashFlow, weightedCashFlow);
	}

	/** 便于重建器直接使用已经聚合的净现金流和加权现金流。 */
	public Result fromComponents(
		BigDecimal beginValue,
		BigDecimal endValue,
		BigDecimal netCashFlow,
		BigDecimal weightedCashFlow) {
		if (beginValue == null || endValue == null || netCashFlow == null || weightedCashFlow == null) {
			throw new InvestmentDomainException("Modified Dietz 金额参数不能为空。");
		}
		BigDecimal profit = endValue.subtract(beginValue).subtract(netCashFlow);
		BigDecimal denominator = beginValue.add(weightedCashFlow);
		BigDecimal rate = denominator.signum() <= 0
			? null : profit.divide(denominator, 24, java.math.RoundingMode.HALF_UP);
		return new Result(profit, rate, denominator, netCashFlow);
	}

	public record CashFlow(Instant occurredAt, BigDecimal amount) {
		public CashFlow {
			if (occurredAt == null || amount == null) {
				throw new InvestmentDomainException("Modified Dietz 现金流不完整。");
			}
		}
	}

	public record Result(
		BigDecimal profit,
		BigDecimal returnRate,
		BigDecimal denominator,
		BigDecimal netCashFlow) {
	}
}
