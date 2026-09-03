package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import app.ziji.investment.domain.InvestmentSide;

/** 成交创建和列表使用的应用结果，不携带 Ledger 内部科目。 */
public record InvestmentTradeResult(
	UUID id,
	UUID transactionId,
	UUID investmentAccountId,
	UUID instrumentId,
	InvestmentSide side,
	BigDecimal quantity,
	BigDecimal unitPrice,
	String currency,
	BigDecimal grossAmount,
	BigDecimal feeAmount,
	BigDecimal taxAmount,
	Instant tradeAt) {
}
