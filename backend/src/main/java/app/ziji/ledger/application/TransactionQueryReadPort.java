package app.ziji.ledger.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.ledger.domain.Transaction;

/** Ledger 交易查询公开端口；SQL、jOOQ/JDBC 和表结构只留在 infrastructure。 */
public interface TransactionQueryReadPort {

	List<TransactionSnapshot> listVisible(
		Set<UUID> visibleAccountIds,
		TransactionQuery query,
		TransactionKeysetPosition after,
		int maximumRecords);

	boolean hasVisibleBoundary(
		Set<UUID> visibleAccountIds,
		TransactionQuery query,
		TransactionKeysetPosition position);

	Optional<TransactionSnapshot> findVisible(Set<UUID> visibleAccountIds, UUID transactionId);

	record TransactionSnapshot(
		Transaction transaction,
		int entityVersion) {

		public TransactionSnapshot {
			if (transaction == null || entityVersion < 1) {
				throw new TransactionQueryValidationException();
			}
		}
	}
}
