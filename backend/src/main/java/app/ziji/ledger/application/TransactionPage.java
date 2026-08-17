package app.ziji.ledger.application;

import java.util.List;

/** 交易列表结果；nextCursor 为空表示已经到达末页。 */
public record TransactionPage(
		List<TransactionQueryReadPort.TransactionSnapshot> transactions,
		String nextCursor,
		boolean hasMore) {

	public TransactionPage {
		transactions = List.copyOf(transactions);
	}
}
