package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 退款语义命令；分类从原支出事实继承，不伪装为收入。 */
public record RefundCommand(
	UUID userId,
	UUID accountId,
	UUID originalTransactionId,
	Money amount,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String note) {

	public RefundCommand {
		require(userId, "用户");
		require(accountId, "账户");
		require(originalTransactionId, "原支出交易");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
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
