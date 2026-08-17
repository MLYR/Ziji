package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 同币种转账命令；手续费作为独立费用分录。 */
public record TransferCommand(
	UUID userId,
	UUID fromAccountId,
	UUID toAccountId,
	UUID feeLedgerAccountId,
	UUID feeCategoryId,
	Money amount,
	Money feeAmount,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String note) {

	public TransferCommand {
		require(userId, "用户");
		require(fromAccountId, "转出账户");
		require(toAccountId, "转入账户");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		if (fromAccountId.equals(toAccountId)) {
			throw new LedgerCommandValidationException("转出和转入账户不能相同。");
		}
		if (feeAmount != null && feeAmount.amount().signum() > 0 && feeCategoryId == null) {
			throw new LedgerCommandValidationException("有手续费时必须提供费用分类。");
		}
		if (feeAmount != null && feeAmount.amount().signum() < 0) {
			throw new LedgerCommandValidationException("手续费不能为负数。");
		}
	}

	/** 公开语义构造器只接收费用分类；内部费用科目由 Ledger 在事务内确保。 */
	public TransferCommand(
		UUID userId,
		UUID fromAccountId,
		UUID toAccountId,
		UUID feeCategoryId,
		Money amount,
		Money feeAmount,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String note) {
		this(userId, fromAccountId, toAccountId, null, feeCategoryId, amount, feeAmount,
			businessAt, businessDate, timezone, note);
	}

	private static void require(Object value, String field) {
		if (value == null) {
			throw new LedgerCommandValidationException(field + "不能为空。");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new LedgerCommandValidationException(field + "不能为空。");
		}
	}

	private static void requireMax(String value, int maxCodePoints, String field) {
		if (value != null && value.codePointCount(0, value.length()) > maxCodePoints) {
			throw new LedgerCommandValidationException(field + "长度无效。");
		}
	}
}
