package app.ziji.ledger.application;

import java.util.Optional;
import java.util.UUID;

/** Transaction、LedgerEntry 和交易明细的原子持久化端口。 */
public interface LedgerTransactionStore {

	void persistPosted(PostedTransactionWrite write);

	Optional<RefundCandidate> findRefundCandidate(UUID originalTransactionId);

	record RefundCandidate(
		UUID originalTransactionId,
		UUID createdBy,
		UUID originalAccountId,
		UUID expenseLedgerAccountId,
		UUID categoryId,
		app.ziji.ledger.domain.Money originalAmount,
		app.ziji.ledger.domain.Money refundedAmount) {
	}
}
