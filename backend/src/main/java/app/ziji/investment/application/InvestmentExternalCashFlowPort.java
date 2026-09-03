package app.ziji.investment.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** XIRR 使用的投资组合边界外现金流；内部买卖不得由实现重复暴露为现金流。 */
public interface InvestmentExternalCashFlowPort {

	List<CashFlow> list(UUID investmentAccountId, Instant from, Instant to);

	record CashFlow(Instant occurredAt, java.math.BigDecimal amount) {
	}
}
