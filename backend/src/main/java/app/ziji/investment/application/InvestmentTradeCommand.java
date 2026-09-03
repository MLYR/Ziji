package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import app.ziji.investment.domain.InvestmentSide;

/** 买入、卖出和分红的业务命令；不包含任何 Ledger 科目 ID。 */
public record InvestmentTradeCommand(
	UUID userId,
	UUID tradeId,
	UUID investmentAccountId,
	UUID instrumentId,
	InvestmentSide side,
	BigDecimal quantity,
	BigDecimal unitPrice,
	BigDecimal dividendAmount,
	String currency,
	BigDecimal feeAmount,
	BigDecimal taxAmount,
	Instant tradeAt,
	String timezone,
	String note) {

	public InvestmentTradeCommand {
		if (userId == null || investmentAccountId == null || instrumentId == null || side == null
			|| currency == null || feeAmount == null || taxAmount == null || tradeAt == null
			|| timezone == null || timezone.isBlank()) {
			throw new InvestmentRequestValidationException("投资成交命令参数不完整。");
		}
		if (feeAmount.signum() < 0 || taxAmount.signum() < 0) {
			throw new InvestmentBusinessRuleException("手续费和税费不能为负数。");
		}
		switch (side) {
			case BUY, SELL -> {
				if (quantity == null || unitPrice == null || dividendAmount != null
					|| quantity.signum() <= 0 || unitPrice.signum() <= 0) {
					throw new InvestmentBusinessRuleException("买入或卖出必须提供正数量和正价格。");
				}
			}
			case DIVIDEND -> {
				if (quantity != null || unitPrice != null || dividendAmount == null || dividendAmount.signum() <= 0) {
					throw new InvestmentBusinessRuleException("分红必须只提供正分红金额。");
				}
			}
		}
	}

	public InvestmentTradeCommand(
		UUID userId,
		UUID investmentAccountId,
		UUID instrumentId,
		InvestmentSide side,
		BigDecimal quantity,
		BigDecimal unitPrice,
		BigDecimal dividendAmount,
		String currency,
		BigDecimal feeAmount,
		BigDecimal taxAmount,
		Instant tradeAt,
		String timezone,
		String note) {
		this(userId, null, investmentAccountId, instrumentId, side, quantity, unitPrice, dividendAmount,
			currency, feeAmount, taxAmount, tradeAt, timezone, note);
	}
}
