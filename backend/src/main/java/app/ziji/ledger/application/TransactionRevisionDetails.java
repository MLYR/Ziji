package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 修订命令的受控语义载荷；客户端不能直接提交任意借贷分录。 */
public sealed interface TransactionRevisionDetails
	permits TransactionRevisionDetails.Income,
	TransactionRevisionDetails.Expense,
	TransactionRevisionDetails.Transfer,
	TransactionRevisionDetails.Refund,
	TransactionRevisionDetails.BalanceAdjustment {

	record Income(Money amount, UUID incomeLedgerAccountId, UUID categoryId)
		implements TransactionRevisionDetails {
	}

	record Expense(Money amount, UUID expenseLedgerAccountId, UUID categoryId)
		implements TransactionRevisionDetails {
	}

	record Transfer(
		UUID fromAccountId,
		UUID toAccountId,
		UUID feeLedgerAccountId,
		UUID feeCategoryId,
		Money amount,
		Money feeAmount) implements TransactionRevisionDetails {
	}

	record Refund(UUID accountId, UUID originalTransactionId, Money amount)
		implements TransactionRevisionDetails {
	}

	record BalanceAdjustment(
		UUID accountId,
		UUID equityLedgerAccountId,
		Money actualBalance,
		String reason) implements TransactionRevisionDetails {
	}
}
