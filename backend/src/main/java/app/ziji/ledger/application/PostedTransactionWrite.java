package app.ziji.ledger.application;

import java.util.List;
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
	List<UUID> tagIds,
	TransactionWriteDetails details) {

	public PostedTransactionWrite {
		if (transaction == null || createdBy == null || details == null) {
			throw new LedgerCommandValidationException("交易写入载荷不完整。");
		}
		if (tagIds == null) {
			throw new LedgerCommandValidationException("交易标签列表不能为空。");
		}
		tagIds = List.copyOf(tagIds);
	}

	/** 既有无标签事实调用继续可用；冲正事实不携带标签。 */
	public PostedTransactionWrite(
		Transaction transaction,
		UUID createdBy,
		String counterparty,
		String merchant,
		String note,
		UUID categoryId,
		TransactionWriteDetails details) {
		this(transaction, createdBy, counterparty, merchant, note, categoryId, List.of(), details);
	}
}
