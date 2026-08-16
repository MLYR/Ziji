package app.ziji.ledger.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ledger 面向同步消费者的最小只读边界；不暴露 jOOQ、分录或金额。 */
@org.springframework.modulith.NamedInterface("sync-read")
public interface LedgerTransactionSyncReadPort {

	Optional<Snapshot> findForSync(UUID transactionId);

	record Snapshot(
		UUID transactionId,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
		int entityVersion,
		String status,
		List<UUID> accountIds) {
		public Snapshot {
			if (transactionId == null || rootTransactionId == null || versionNo <= 0 || entityVersion <= 0
				|| status == null || status.isBlank() || accountIds == null) {
				throw new IllegalArgumentException("同步交易快照无效。");
			}
			accountIds = List.copyOf(accountIds);
		}
	}
}
