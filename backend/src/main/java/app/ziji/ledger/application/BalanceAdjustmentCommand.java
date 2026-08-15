package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 余额调整命令；before_balance 必须由事实查询计算。 */
public record BalanceAdjustmentCommand(
	UUID userId,
	UUID accountId,
	UUID equityLedgerAccountId,
	Money actualBalance,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String reason) {

	public BalanceAdjustmentCommand {
		require(userId, "用户");
		require(accountId, "账户");
		require(equityLedgerAccountId, "余额调整权益科目");
		require(actualBalance, "实际余额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireText(reason, "调整原因");
		if (reason.codePointCount(0, reason.length()) > 500) {
			throw new LedgerCommandValidationException("调整原因长度无效。");
		}
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
