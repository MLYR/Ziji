package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;

/** 借款到账语义命令；资产和负债科目由 Ledger 根据可见账户事实决定。 */
public record LiabilityBorrowingCommand(
	UUID userId,
	UUID assetAccountId,
	UUID liabilityAccountId,
	Money amount,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String note,
	List<UUID> tagIds) {

	public LiabilityBorrowingCommand {
		require(userId, "用户");
		require(assetAccountId, "收款资产账户");
		require(liabilityAccountId, "借款负债账户");
		require(amount, "金额");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(note, 2000, "备注");
		if (tagIds == null) {
			throw new LedgerCommandValidationException("标签不能为空。");
		}
		if (assetAccountId.equals(liabilityAccountId)) {
			throw new LedgerCommandValidationException("收款资产账户和借款负债账户不能相同。");
		}
	}

	/** 请求层的 currency 语义由 Money 唯一携带，避免出现两份可不一致的币种来源。 */
	public CurrencyCode currency() {
		return amount.currency();
	}

	/** 现有内部调用继续使用无标签语义；HTTP 与修订入口可显式携带标签事实。 */
	public LiabilityBorrowingCommand(
		UUID userId,
		UUID assetAccountId,
		UUID liabilityAccountId,
		Money amount,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String note) {
		this(userId, assetAccountId, liabilityAccountId, amount, businessAt, businessDate, timezone,
			note, List.of());
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
