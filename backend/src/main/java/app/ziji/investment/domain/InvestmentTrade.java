package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;

/** 投资成交事实；手续费和税费保留为独立费用字段，不并入成交成本。 */
public record InvestmentTrade(
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

	public InvestmentTrade {
		if (id == null || transactionId == null || investmentAccountId == null || instrumentId == null
			|| side == null || currency == null || currency.length() != 3 || grossAmount == null
			|| feeAmount == null || taxAmount == null || tradeAt == null) {
			throw new InvestmentDomainException("投资成交事实不完整。");
		}
		if (!currency.equals(currency.toUpperCase(java.util.Locale.ROOT))) {
			throw new InvestmentDomainException("投资成交币种必须使用大写代码。");
		}
		if (grossAmount.signum() <= 0 || feeAmount.signum() < 0 || taxAmount.signum() < 0) {
			throw new InvestmentDomainException("投资成交金额或费用金额无效。");
		}
		// PostgreSQL NUMERIC 会保留列定义的小数位，业务精度必须按币种值判断，不能直接比较 BigDecimal.scale。
		CurrencyCode parsedCurrency = CurrencyCode.fromCode(currency);
		if (!new Money(feeAmount, parsedCurrency).hasPostingPrecision()
			|| !new Money(taxAmount, parsedCurrency).hasPostingPrecision()
			|| !new Money(grossAmount, parsedCurrency).hasPostingPrecision()) {
			throw new InvestmentDomainException("投资成交金额超过币种入账精度。");
		}
		switch (side) {
			case BUY, SELL -> {
				if (quantity == null || unitPrice == null || quantity.signum() <= 0 || unitPrice.signum() <= 0
					|| quantity.scale() > 12 || unitPrice.scale() > 12) {
					throw new InvestmentDomainException("买卖成交数量或价格无效。");
				}
			}
			case DIVIDEND -> {
				if (quantity != null || unitPrice != null) {
					throw new InvestmentDomainException("分红成交不能携带数量或单价。");
				}
			}
		}
	}
}
