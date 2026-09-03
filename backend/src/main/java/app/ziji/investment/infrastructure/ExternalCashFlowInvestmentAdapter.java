package app.ziji.investment.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.ziji.investment.application.InvestmentExternalCashFlowPort;
import app.ziji.ledger.application.InvestmentCashReadPort;
import org.springframework.stereotype.Component;

/** 将 Ledger 账户边界现金流转换为投资收益计算所需的内部端口。 */
@Component
public final class ExternalCashFlowInvestmentAdapter implements InvestmentExternalCashFlowPort {

	private final InvestmentCashReadPort cashFlows;

	public ExternalCashFlowInvestmentAdapter(InvestmentCashReadPort cashFlows) {
		this.cashFlows = java.util.Objects.requireNonNull(cashFlows, "投资边界现金流入口不能为空。");
	}

	@Override
	public List<CashFlow> list(UUID investmentAccountId, Instant from, Instant to) {
		return cashFlows.listExternalCashFlows(investmentAccountId, from, to).stream()
			.map(flow -> new CashFlow(flow.occurredAt(), flow.amount())).toList();
	}
}
