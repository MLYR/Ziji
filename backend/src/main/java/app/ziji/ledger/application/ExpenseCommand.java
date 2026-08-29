package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
	String note,
	List<UUID> tagIds) {

	public ExpenseCommand {
		require(userId, "用户");
		require(accountId, "账户");
		require(categoryId, "支出分类");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(merchant, 200, "商户");
		if (tagIds == null) {
			throw new LedgerCommandValidationException("标签不能为空。");
		}
	}

	/** 公开语义构造器不接收内部费用科目；Ledger 在事务内按分类惰性确保。 */
	public ExpenseCommand(
		UUID userId,
		UUID accountId,
		UUID categoryId,
		Money amount,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String merchant,
		String note) {
		this(userId, accountId, null, categoryId, amount, businessAt, businessDate, timezone, merchant,
			note, List.of());
	}

	/** 现有内部调用继续使用无标签语义；HTTP 与修订入口可显式携带标签事实。 */
	public ExpenseCommand(
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
		this(userId, accountId, expenseLedgerAccountId, categoryId, amount, businessAt, businessDate, timezone,
			merchant, note, List.of());
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
