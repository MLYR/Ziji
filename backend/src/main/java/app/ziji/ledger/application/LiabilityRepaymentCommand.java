package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;

/** 负债还款语义命令；本金、利息和手续费在同一笔平衡交易内表达。 */
public record LiabilityRepaymentCommand(
	UUID userId,
	UUID cashAccountId,
	UUID liabilityAccountId,
	Money principalAmount,
	Money interestAmount,
	Money feeAmount,
	UUID interestCategoryId,
	UUID feeCategoryId,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String note,
	List<UUID> tagIds) {

	public LiabilityRepaymentCommand {
		require(userId, "用户");
		require(cashAccountId, "付款资产账户");
		require(liabilityAccountId, "负债账户");
		require(principalAmount, "本金");
		require(interestAmount, "利息");
		require(feeAmount, "手续费");
		require(businessAt, "业务时间");
		require(businessDate, "业务日期");
		requireText(timezone, "时区");
		requireMax(timezone, 64, "时区");
		requireMax(note, 2000, "备注");
		if (tagIds == null) {
			throw new LedgerCommandValidationException("标签不能为空。");
		}
		if (cashAccountId.equals(liabilityAccountId)) {
			throw new LedgerCommandValidationException("付款资产账户和负债账户不能相同。");
		}
	}

	/** 三个金额必须共享同一 currency；服务在账户边界再次校验该不变量。 */
	public CurrencyCode currency() {
		return principalAmount.currency();
	}

	/** 现有内部调用继续使用无标签语义；HTTP 与修订入口可显式携带标签事实。 */
	public LiabilityRepaymentCommand(
		UUID userId,
		UUID cashAccountId,
		UUID liabilityAccountId,
		Money principalAmount,
		Money interestAmount,
		Money feeAmount,
		UUID interestCategoryId,
		UUID feeCategoryId,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String note) {
		this(userId, cashAccountId, liabilityAccountId, principalAmount, interestAmount, feeAmount,
			interestCategoryId, feeCategoryId, businessAt, businessDate, timezone, note, List.of());
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
