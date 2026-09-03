package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** 单一标的移动加权平均成本状态；成本不含手续费和税费。 */
public record Position(
	UUID instrumentId,
	BigDecimal quantity,
	BigDecimal costBasis,
	BigDecimal averageCost) {

	public Position {
		if (instrumentId == null || quantity == null || costBasis == null || averageCost == null
			|| quantity.signum() < 0 || costBasis.signum() < 0 || averageCost.signum() < 0) {
			throw new InvestmentDomainException("持仓状态无效。");
		}
		if (quantity.signum() == 0 && (costBasis.signum() != 0 || averageCost.signum() != 0)) {
			throw new InvestmentDomainException("清仓后持仓成本必须归零。");
		}
	}

	public static Position empty(UUID instrumentId) {
		return new Position(instrumentId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
	}

	public Position buy(BigDecimal buyQuantity, BigDecimal grossAmount) {
		if (buyQuantity == null || grossAmount == null || buyQuantity.signum() <= 0 || grossAmount.signum() <= 0) {
			throw new InvestmentDomainException("买入数量和成交金额必须大于零。");
		}
		BigDecimal nextQuantity = quantity.add(buyQuantity);
		BigDecimal nextCost = costBasis.add(grossAmount);
		return new Position(instrumentId, nextQuantity, nextCost, nextCost.divide(nextQuantity, 24,
			java.math.RoundingMode.HALF_UP));
	}

	public SellAllocation sell(BigDecimal sellQuantity) {
		if (sellQuantity == null || sellQuantity.signum() <= 0) {
			throw new InvestmentDomainException("卖出数量必须大于零。");
		}
		if (sellQuantity.compareTo(quantity) > 0) {
			throw new InvestmentDomainException("卖出数量超过当前持仓。");
		}
		BigDecimal releasedCost = sellQuantity.compareTo(quantity) == 0
			? costBasis : sellQuantity.multiply(averageCost);
		BigDecimal nextQuantity = quantity.subtract(sellQuantity);
		BigDecimal nextCost = nextQuantity.signum() == 0 ? BigDecimal.ZERO : costBasis.subtract(releasedCost);
		BigDecimal nextAverage = nextQuantity.signum() == 0
			? BigDecimal.ZERO : nextCost.divide(nextQuantity, 24, java.math.RoundingMode.HALF_UP);
		return new SellAllocation(releasedCost, new Position(instrumentId, nextQuantity, nextCost, nextAverage));
	}

	public record SellAllocation(BigDecimal releasedCost, Position remaining) {
		public SellAllocation {
			if (releasedCost == null || releasedCost.signum() < 0 || remaining == null) {
				throw new InvestmentDomainException("卖出成本分配无效。");
			}
		}
	}
}
