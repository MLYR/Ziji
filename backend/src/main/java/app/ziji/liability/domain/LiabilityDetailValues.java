package app.ziji.liability.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Set;

/** 六个可选业务字段的类型化快照；格式范围与账户事实规则分别校验。 */
public record LiabilityDetailValues(
	BigDecimal interestRate,
	LocalDate loanDate,
	LocalDate dueDate,
	Integer billingDay,
	Integer repaymentDay,
	BigDecimal currentAmountDue) {

	private static final Set<String> CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	public LiabilityDetailValues {
		if (interestRate != null && (interestRate.signum() < 0 || interestRate.compareTo(BigDecimal.ONE) > 0
			|| interestRate.scale() > 8)) {
			throw new LiabilityDetailException.Validation();
		}
		if (!validDay(billingDay) || !validDay(repaymentDay)) {
			throw new LiabilityDetailException.Validation();
		}
		if (currentAmountDue != null && (currentAmountDue.signum() < 0 || currentAmountDue.scale() > 2
			|| integerDigits(currentAmountDue) > 22)) {
			throw new LiabilityDetailException.Validation();
		}
		// 数据库 NUMERIC 会补齐尾零；领域统一为无指数十进制，保证首次响应与重放一致。
		interestRate = canonical(interestRate);
		currentAmountDue = canonical(currentAmountDue);
	}

	public void validateFor(String accountType, String currency) {
		if (accountType == null || !CURRENCIES.contains(currency)) {
			throw new LiabilityDetailException.Validation();
		}
		switch (accountType) {
			case "CREDIT_CARD" -> {
				if (loanDate != null || dueDate != null) {
					throw new LiabilityDetailException.BusinessRule();
				}
			}
			case "LOAN", "CONSUMER_LOAN" -> {
				if (billingDay != null) {
					throw new LiabilityDetailException.BusinessRule();
				}
			}
			case "OTHER" -> {
			}
			default -> throw new LiabilityDetailException.Validation();
		}
		if (loanDate != null && dueDate != null && dueDate.isBefore(loanDate)) {
			throw new LiabilityDetailException.BusinessRule();
		}
		if (currentAmountDue != null) {
			try {
				currentAmountDue.setScale("JPY".equals(currency) ? 0 : 2, RoundingMode.UNNECESSARY);
			} catch (ArithmeticException exception) {
				// 提醒金额仍必须服从账户币种精度，不能依赖数据库触发器才发现。
				throw new LiabilityDetailException.BusinessRule();
			}
		}
	}

	private static boolean validDay(Integer value) {
		return value == null || value >= 1 && value <= 31;
	}

	private static int integerDigits(BigDecimal value) {
		return Math.max(1, value.precision() - value.scale());
	}

	private static BigDecimal canonical(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
	}
}
