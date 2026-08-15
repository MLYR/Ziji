package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Transaction;

/** 一次原子事实写入的完整载荷。 */
public record PostedTransactionWrite(
	Transaction transaction,
	UUID createdBy,
	String counterparty,
	String merchant,
	String note,
	UUID categoryId,
	TransactionWriteDetails details) {

	public PostedTransactionWrite {
		if (transaction == null || createdBy == null || details == null) {
			throw new LedgerCommandValidationException("交易写入载荷不完整。");
		}
	}
}
