package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;

/** 投资成交的 Ledger 语义命令；调用方不能指定任意现金或系统科目。 */
public record InvestmentLedgerCommand(
	UUID userId,
	UUID tradeId,
	UUID investmentAccountId,
	UUID instrumentId,
	Side side,
	BigDecimal quantity,
	BigDecimal unitPrice,
	BigDecimal grossAmount,
	BigDecimal feeAmount,
	BigDecimal taxAmount,
	BigDecimal sellCostBasis,
	CurrencyCode currency,
	Instant tradeAt,
	LocalDate businessDate,
	String timezone,
	String note) {

	public InvestmentLedgerCommand {
		if (userId == null || investmentAccountId == null || instrumentId == null || side == null
			|| grossAmount == null || feeAmount == null || taxAmount == null || currency == null
			|| tradeAt == null || businessDate == null || timezone == null || timezone.isBlank()) {
			throw new LedgerCommandValidationException("投资入账命令参数不完整。");
		}
		tradeId = tradeId == null ? UUID.randomUUID() : tradeId;
		sellCostBasis = sellCostBasis == null ? BigDecimal.ZERO : sellCostBasis;
		if (grossAmount.signum() <= 0 || feeAmount.signum() < 0 || taxAmount.signum() < 0
			|| sellCostBasis.signum() < 0) {
			throw new LedgerCommandValidationException("投资成交金额或成本基础无效。");
		}
		if (!new Money(grossAmount, currency).hasPostingPrecision()
			|| !new Money(feeAmount, currency).hasPostingPrecision()
			|| !new Money(taxAmount, currency).hasPostingPrecision()
			|| !new Money(sellCostBasis, currency).hasPostingPrecision()) {
			throw new LedgerCommandValidationException("投资金额超过币种入账精度。");
		}
		switch (side) {
			case BUY, SELL -> {
				if (quantity == null || unitPrice == null || quantity.signum() <= 0 || unitPrice.signum() <= 0
					|| quantity.scale() > 12 || unitPrice.scale() > 12) {
					throw new LedgerCommandValidationException("买卖成交数量或价格无效。");
				}
				if (side == Side.BUY && sellCostBasis.signum() != 0) {
					throw new LedgerCommandValidationException("买入不能携带卖出成本基础。");
				}
			}
			case DIVIDEND -> {
				if (quantity != null || unitPrice != null || sellCostBasis.signum() != 0) {
					throw new LedgerCommandValidationException("分红不能携带数量、价格或卖出成本基础。");
				}
			}
		}
	}

	public InvestmentLedgerCommand(
		UUID userId,
		UUID investmentAccountId,
		UUID instrumentId,
		Side side,
		BigDecimal quantity,
		BigDecimal unitPrice,
		BigDecimal grossAmount,
		BigDecimal feeAmount,
		BigDecimal taxAmount,
		BigDecimal sellCostBasis,
		CurrencyCode currency,
		Instant tradeAt,
		LocalDate businessDate,
		String timezone) {
		this(userId, null, investmentAccountId, instrumentId, side, quantity, unitPrice, grossAmount,
			feeAmount, taxAmount, sellCostBasis, currency, tradeAt, businessDate, timezone, null);
	}

	public enum Side {
		BUY,
		SELL,
		DIVIDEND
	}
}
