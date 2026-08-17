package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** repayment_details 的无框架写入模型；分类仍通过费用系统科目保留。 */
public record RepaymentWriteDetails(
	UUID liabilityAccountId,
	UUID cashAccountId,
	Money principalAmount,
	Money interestAmount,
	Money feeAmount,
	UUID interestCategoryId,
	UUID feeCategoryId) implements TransactionWriteDetails {
}
