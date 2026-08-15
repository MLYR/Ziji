package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 收入语义命令；对方科目由应用边界解析，HTTP 尚未在本任务实现。 */
public record IncomeCommand(
	UUID userId,
	UUID accountId,
	UUID incomeLedgerAccountId,
	UUID categoryId,
	Money amount,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String counterparty,
	String note) {

	public IncomeCommand {
		require(userId, "用户");
		require(accountId, "账户");
		require(incomeLedgerAccountId, "收入科目");
		require(categoryId, "收入分类");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(counterparty, 200, "对方");
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
