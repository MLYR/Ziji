package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Instant;

import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldType;

/** 创建或修订占用的类型化业务载荷；公共 API 不携带 source、版本或任何生命周期事实。 */
public record LiquidityHoldCommand(
	LiquidityHoldType type,
	BigDecimal amount,
	AccountCurrency currency,
	Instant effectiveAt,
	Instant expiresAt,
	String reason) {

	public LiquidityHoldCommand {
		if (type == null || currency == null || effectiveAt == null || reason == null || reason.isBlank()
			|| reason.codePointCount(0, reason.length()) > 500 || (expiresAt != null && !expiresAt.isAfter(effectiveAt))) {
			throw new LiquidityHoldException.Validation();
		}
		try {
			LiquidityHold.validateAmountForCurrency(amount, currency);
		} catch (RuntimeException exception) {
			throw new LiquidityHoldException.Validation();
		}
	}
}
