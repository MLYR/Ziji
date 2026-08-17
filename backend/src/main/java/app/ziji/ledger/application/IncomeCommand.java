package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 收入语义命令；公共边界只传业务字段，对方科目由 Ledger 在事务内解析。 */
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
		require(categoryId, "收入分类");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(counterparty, 200, "对方");
	}

	/** 公开语义构造器不接收内部收入科目；Ledger 在事务内按分类惰性确保。 */
	public IncomeCommand(
		UUID userId,
		UUID accountId,
		UUID categoryId,
		Money amount,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String counterparty,
		String note) {
		this(userId, accountId, null, categoryId, amount, businessAt, businessDate, timezone, counterparty, note);
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
