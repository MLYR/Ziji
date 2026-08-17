package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 修订命令的受控语义载荷；客户端不能直接提交任意借贷分录。 */
public sealed interface TransactionRevisionDetails
	permits TransactionRevisionDetails.Income,
		TransactionRevisionDetails.Expense,
		TransactionRevisionDetails.Transfer,
		TransactionRevisionDetails.Refund,
		TransactionRevisionDetails.BalanceAdjustment,
		TransactionRevisionDetails.LiabilityBorrowing,
		TransactionRevisionDetails.LiabilityRepayment {

	record Income(UUID accountId, Money amount, UUID incomeLedgerAccountId, UUID categoryId)
		implements TransactionRevisionDetails {

		public Income(Money amount, UUID incomeLedgerAccountId, UUID categoryId) {
			this(null, amount, incomeLedgerAccountId, categoryId);
		}

		public Income(UUID accountId, Money amount, UUID categoryId) {
			this(accountId, amount, null, categoryId);
		}
	}

	record Expense(UUID accountId, Money amount, UUID expenseLedgerAccountId, UUID categoryId)
		implements TransactionRevisionDetails {

		public Expense(Money amount, UUID expenseLedgerAccountId, UUID categoryId) {
			this(null, amount, expenseLedgerAccountId, categoryId);
		}

		public Expense(UUID accountId, Money amount, UUID categoryId) {
			this(accountId, amount, null, categoryId);
		}
	}

	record Transfer(
		UUID fromAccountId,
		UUID toAccountId,
		UUID feeLedgerAccountId,
		UUID feeCategoryId,
		Money amount,
		Money feeAmount) implements TransactionRevisionDetails {

		public Transfer(
			UUID fromAccountId,
			UUID toAccountId,
			UUID feeCategoryId,
			Money amount,
			Money feeAmount) {
			this(fromAccountId, toAccountId, null, feeCategoryId, amount, feeAmount);
		}
	}

	record Refund(UUID accountId, UUID originalTransactionId, Money amount)
		implements TransactionRevisionDetails {
	}

	record BalanceAdjustment(
		UUID accountId,
		UUID equityLedgerAccountId,
		Money actualBalance,
		String reason) implements TransactionRevisionDetails {

		public BalanceAdjustment(UUID accountId, Money actualBalance, String reason) {
			this(accountId, null, actualBalance, reason);
		}
	}

	record LiabilityBorrowing(
		UUID assetAccountId,
		UUID liabilityAccountId,
		Money amount) implements TransactionRevisionDetails {
	}

	record LiabilityRepayment(
		UUID cashAccountId,
		UUID liabilityAccountId,
		Money principalAmount,
		Money interestAmount,
		Money feeAmount,
		UUID interestCategoryId,
		UUID feeCategoryId) implements TransactionRevisionDetails {
	}
}
