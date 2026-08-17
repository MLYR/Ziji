package app.ziji.ledger.application;

import java.time.Instant;
import java.util.List;

/** 余额事实汇总和可重建快照的公开持久化边界；SQL 仅存在于 infrastructure。 */
public interface BalanceProjectionStore {

	List<AccountBalanceSnapshot> aggregatePostedEntries();

	List<AccountBalanceSnapshot> readSnapshots();

	void replaceSnapshots(List<AccountBalanceSnapshot> snapshots, Instant calculatedAt);
}
