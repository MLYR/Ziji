package app.ziji.liability.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** PATCH 使用 present 标记区分字段缺失与显式 null。 */
public record LiabilityDetailPatch(
	boolean interestRatePresent,
	BigDecimal interestRate,
	boolean loanDatePresent,
	LocalDate loanDate,
	boolean dueDatePresent,
	LocalDate dueDate,
	boolean billingDayPresent,
	Integer billingDay,
	boolean repaymentDayPresent,
	Integer repaymentDay,
	boolean currentAmountDuePresent,
	BigDecimal currentAmountDue) {

	public LiabilityDetailPatch {
		if (!interestRatePresent && interestRate != null
			|| !loanDatePresent && loanDate != null
			|| !dueDatePresent && dueDate != null
			|| !billingDayPresent && billingDay != null
			|| !repaymentDayPresent && repaymentDay != null
			|| !currentAmountDuePresent && currentAmountDue != null) {
			throw new LiabilityDetailException.Validation();
		}
		LiabilityDetailValues normalized = new LiabilityDetailValues(
			interestRate, loanDate, dueDate, billingDay, repaymentDay, currentAmountDue);
		interestRate = normalized.interestRate();
		currentAmountDue = normalized.currentAmountDue();
		if (isEmpty(interestRatePresent, loanDatePresent, dueDatePresent,
			billingDayPresent, repaymentDayPresent, currentAmountDuePresent)) {
			throw new LiabilityDetailException.Validation();
		}
	}

	public LiabilityDetailValues applyTo(LiabilityDetailValues current) {
		if (current == null) {
			throw new LiabilityDetailException.Validation();
		}
		return new LiabilityDetailValues(
			interestRatePresent ? interestRate : current.interestRate(),
			loanDatePresent ? loanDate : current.loanDate(),
			dueDatePresent ? dueDate : current.dueDate(),
			billingDayPresent ? billingDay : current.billingDay(),
			repaymentDayPresent ? repaymentDay : current.repaymentDay(),
			currentAmountDuePresent ? currentAmountDue : current.currentAmountDue());
	}

	private static boolean isEmpty(boolean... present) {
		for (boolean value : present) {
			if (value) {
				return false;
			}
		}
		return true;
	}
}
