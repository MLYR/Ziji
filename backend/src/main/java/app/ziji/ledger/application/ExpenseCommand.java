package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 支出语义命令；费用科目必须以独立借方分录表达。 */
public record ExpenseCommand(
	UUID userId,
	UUID accountId,
	UUID expenseLedgerAccountId,
	UUID categoryId,
	Money amount,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String merchant,
	String note) {

	public ExpenseCommand {
		require(userId, "用户");
		require(accountId, "账户");
		require(expenseLedgerAccountId, "支出科目");
		require(categoryId, "支出分类");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(merchant, 200, "商户");
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
