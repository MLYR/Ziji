package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import app.ziji.account.domain.AccountCurrency;

/** 余额读取的精确 application 快照；金额仍以 BigDecimal 保持十进制语义，HTTP 层再转为字符串。 */
public record AccountBalanceResult(
	UUID accountId,
	AccountCurrency currency,
	BigDecimal ledgerBalance,
	BigDecimal unavailableAmount,
	UnavailableBreakdown unavailableBreakdown,
	BigDecimal availableBalance,
	LiquidityStatus liquidityStatus,
	Instant asOf,
	int asOfSequence) {

	public AccountBalanceResult {
		Objects.requireNonNull(accountId, "账户 ID 不能为空。");
		Objects.requireNonNull(currency, "账户币种不能为空。");
		Objects.requireNonNull(ledgerBalance, "账面余额不能为空。");
		Objects.requireNonNull(unavailableAmount, "不可用金额不能为空。");
		Objects.requireNonNull(unavailableBreakdown, "不可用金额明细不能为空。");
		Objects.requireNonNull(availableBalance, "可用余额不能为空。");
		Objects.requireNonNull(liquidityStatus, "流动性状态不能为空。");
		Objects.requireNonNull(asOf, "余额评估时点不能为空。");
		if (asOfSequence != 0 || unavailableAmount.signum() < 0
			|| unavailableAmount.compareTo(unavailableBreakdown.total()) != 0
			|| availableBalance.compareTo(ledgerBalance.subtract(unavailableAmount)) != 0
			|| (availableBalance.signum() < 0) != (liquidityStatus == LiquidityStatus.NEGATIVE_AVAILABLE)) {
			throw new IllegalArgumentException("余额聚合快照不一致。");
		}
	}

	public record UnavailableBreakdown(
		BigDecimal frozen,
		BigDecimal inTransit,
		BigDecimal reserved) {

		public UnavailableBreakdown {
			Objects.requireNonNull(frozen, "冻结金额不能为空。");
			Objects.requireNonNull(inTransit, "在途金额不能为空。");
			Objects.requireNonNull(reserved, "预留金额不能为空。");
			if (frozen.signum() < 0 || inTransit.signum() < 0 || reserved.signum() < 0) {
				throw new IllegalArgumentException("不可用金额不能为负数。");
			}
		}

		public BigDecimal total() {
			return frozen.add(inTransit).add(reserved);
		}
	}

	public enum LiquidityStatus {
		NORMAL,
		NEGATIVE_AVAILABLE
	}
}
