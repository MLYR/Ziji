package app.ziji.ledger.application;

import java.util.Optional;
import java.util.UUID;

import app.ziji.ledger.domain.Transaction;

/** Transaction、LedgerEntry 和交易明细的原子持久化端口。 */
public interface LedgerTransactionStore {

	void persistPosted(PostedTransactionWrite write);

	Optional<RefundCandidate> findRefundCandidate(UUID originalTransactionId);

	/** 以数据库行锁读取已确认交易，供修改和作废在同一事务内串行化并判定陈旧版本。 */
	Optional<PostedTransactionSnapshot> findPostedForMutation(UUID transactionId);

	void persistRevision(TransactionRevisionWrite write);

	void persistVoid(TransactionVoidWrite write);

	record RefundCandidate(
		UUID originalTransactionId,
		UUID createdBy,
		UUID originalAccountId,
		UUID expenseLedgerAccountId,
		UUID categoryId,
		app.ziji.ledger.domain.Money originalAmount,
		app.ziji.ledger.domain.Money refundedAmount) {
	}

	record PostedTransactionSnapshot(
		Transaction transaction,
		int entityVersion,
		boolean hasDependentFacts,
		String counterparty,
		String merchant,
		String note,
		UUID categoryId,
		TransactionWriteDetails details) {
		public PostedTransactionSnapshot {
			if (transaction == null || entityVersion <= 0 || details == null) {
				throw new LedgerCommandValidationException("已确认交易快照无效。");
			}
		}
	}

	record TransactionRevisionWrite(
		UUID originalTransactionId,
		int expectedEntityVersion,
		String reason,
		Transaction reversal,
		PostedTransactionWrite replacement) {
		public TransactionRevisionWrite {
			if (originalTransactionId == null || expectedEntityVersion <= 0 || reason == null || reason.isBlank()
				|| reversal == null || replacement == null) {
				throw new LedgerCommandValidationException("交易修订写入载荷不完整。");
			}
		}
	}

	record TransactionVoidWrite(
		UUID originalTransactionId,
		int expectedEntityVersion,
		UUID updatedBy,
		String reason,
		Transaction reversal) {
		public TransactionVoidWrite {
			if (originalTransactionId == null || expectedEntityVersion <= 0 || updatedBy == null || reason == null
				|| reason.isBlank() || reversal == null) {
				throw new LedgerCommandValidationException("交易作废写入载荷不完整。");
			}
		}
	}
}
