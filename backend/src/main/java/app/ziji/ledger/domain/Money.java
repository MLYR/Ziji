package app.ziji.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 带币种的精确金额值对象；不在构造或加法时静默舍入，分录方向由 LedgerEntry 负责表达。 */
public final class Money implements Comparable<Money> {

	private final BigDecimal amount;
	private final CurrencyCode currency;

	public Money(BigDecimal amount, CurrencyCode currency) {
		if (amount == null) {
			throw new LedgerDomainException("金额不能为空。");
		}
		if (currency == null) {
			throw new LedgerDomainException("币种不能为空。");
		}
		this.amount = amount;
		this.currency = currency;
	}

	public BigDecimal amount() {
		return amount;
	}

	public CurrencyCode currency() {
		return currency;
	}

	public boolean hasPostingPrecision() {
		try {
			amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
			return true;
		} catch (ArithmeticException exception) {
			return false;
		}
	}

	/** 唯一的显式入账归一化入口；普通构造和加法不会自动舍入。 */
	public Money roundHalfUpForPosting() {
		return new Money(amount.setScale(currency.minorUnits(), RoundingMode.HALF_UP), currency);
	}

	/** 仅允许同币种相加，保留 BigDecimal 的原始精度。 */
	public Money add(Money other) {
		if (other == null) {
			throw new LedgerDomainException("相加金额不能为空。");
		}
		ensureSameCurrency(other);
		return new Money(amount.add(other.amount), currency);
	}

	/** 仅允许同币种比较，比较使用 BigDecimal 的精确数值语义。 */
	@Override
	public int compareTo(Money other) {
		if (other == null) {
			throw new LedgerDomainException("比较金额不能为空。");
		}
		ensureSameCurrency(other);
		return amount.compareTo(other.amount);
	}

	private void ensureSameCurrency(Money other) {
		if (currency != other.currency) {
			throw new LedgerDomainException("不同币种的金额不能直接运算。");
		}
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Money money)) {
			return false;
		}
		return currency == money.currency && amount.compareTo(money.amount) == 0;
	}

	@Override
	public int hashCode() {
		return 31 * currency.hashCode() + amount.stripTrailingZeros().hashCode();
	}
}
