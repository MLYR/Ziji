package app.ziji.ledger.application;

import java.time.LocalDate;
import java.util.UUID;

/** 交易流水按 businessDate、transactionId 倒序分页的边界。 */
public record TransactionKeysetPosition(LocalDate businessDate, UUID transactionId) {

	public TransactionKeysetPosition {
		if (businessDate == null || transactionId == null) {
			throw new TransactionQueryValidationException();
		}
	}
}
