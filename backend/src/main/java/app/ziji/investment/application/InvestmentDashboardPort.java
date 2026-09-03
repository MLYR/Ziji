package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 供 statistics 注入的投资只读端口；statistics 不得访问 investment 表或复制估值算法。
 */
public interface InvestmentDashboardPort {

	InvestmentDashboardSnapshot getDashboard(UUID userId, Instant asOf);

	record InvestmentDashboardSnapshot(
		String baseCurrency,
		BigDecimal brokerCash,
		BigDecimal valuedPositionMarketValue,
		int unpricedInstrumentCount,
		int staleMarketDataCount) {

		public InvestmentDashboardSnapshot(
			String baseCurrency,
			BigDecimal brokerCash,
			BigDecimal valuedPositionMarketValue,
			int unpricedInstrumentCount) {
			this(baseCurrency, brokerCash, valuedPositionMarketValue, unpricedInstrumentCount, 0);
		}

		public InvestmentDashboardSnapshot {
			if (baseCurrency == null || brokerCash == null || valuedPositionMarketValue == null
				|| brokerCash.scale() < 0 || valuedPositionMarketValue.scale() < 0
				|| unpricedInstrumentCount < 0 || staleMarketDataCount < 0) {
				throw new InvestmentApplicationException("投资 Dashboard 快照不完整。");
			}
		}
	}
}
