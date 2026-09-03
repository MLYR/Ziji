package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 投资模块读取券商现金和账户边界现金流的公开端口；不暴露 Ledger 表或科目 ID。 */
@org.springframework.modulith.NamedInterface("application")
public interface InvestmentCashReadPort {

	CashBalance findCashBalance(UUID investmentAccountId, Instant asOf);

	List<ExternalCashFlow> listExternalCashFlows(UUID investmentAccountId, Instant from, Instant to);

	record CashBalance(String currency, BigDecimal amount) {
	}

	record ExternalCashFlow(Instant occurredAt, BigDecimal amount) {
	}
}
