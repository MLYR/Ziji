package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;

/** 投资交易表的一对一结构化明细，与 Ledger 交易事实同事务写入。 */
public record InvestmentTradeWriteDetails(
	UUID tradeId,
	UUID investmentAccountId,
	UUID instrumentId,
	InvestmentLedgerCommand.Side side,
	BigDecimal quantity,
	BigDecimal unitPrice,
	CurrencyCode currency,
	BigDecimal grossAmount,
	BigDecimal feeAmount,
	BigDecimal taxAmount,
	Instant tradeAt) implements TransactionWriteDetails {

	public InvestmentTradeWriteDetails {
		if (tradeId == null || investmentAccountId == null || instrumentId == null || side == null
			|| currency == null || grossAmount == null || feeAmount == null || taxAmount == null || tradeAt == null) {
			throw new LedgerCommandValidationException("投资交易明细不完整。");
		}
	}
}
