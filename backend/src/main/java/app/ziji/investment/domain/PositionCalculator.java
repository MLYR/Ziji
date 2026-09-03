package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按成交时间重建持仓；卖出前先检查数量，禁止生成负持仓。 */
public final class PositionCalculator {

	public AppliedTrade apply(Position current, InvestmentTrade trade) {
		if (current == null || trade == null || !current.instrumentId().equals(trade.instrumentId())) {
			throw new InvestmentDomainException("持仓与成交标的不匹配。");
		}
		return switch (trade.side()) {
			case BUY -> new AppliedTrade(current, current.buy(trade.quantity(), trade.grossAmount()), BigDecimal.ZERO);
			case SELL -> {
				Position.SellAllocation allocation = current.sell(trade.quantity());
				yield new AppliedTrade(current, allocation.remaining(), allocation.releasedCost());
			}
			case DIVIDEND -> new AppliedTrade(current, current, BigDecimal.ZERO);
		};
	}

	public Map<UUID, Position> rebuild(List<InvestmentTrade> trades) {
		if (trades == null) {
			throw new InvestmentDomainException("成交列表不能为空。");
		}
		Map<UUID, Position> positions = new LinkedHashMap<>();
		trades.stream()
			.sorted(Comparator.comparing(InvestmentTrade::tradeAt).thenComparing(InvestmentTrade::id))
			.forEach(trade -> {
				Position current = positions.getOrDefault(trade.instrumentId(), Position.empty(trade.instrumentId()));
				positions.put(trade.instrumentId(), apply(current, trade).remaining());
			});
		return Map.copyOf(positions);
	}

	public record AppliedTrade(Position before, Position remaining, BigDecimal releasedCost) {
		public AppliedTrade {
			if (before == null || remaining == null || releasedCost == null || releasedCost.signum() < 0) {
				throw new InvestmentDomainException("成交后的持仓状态无效。");
			}
		}
	}
}
