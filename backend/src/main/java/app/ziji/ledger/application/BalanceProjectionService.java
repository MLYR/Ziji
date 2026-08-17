package app.ziji.ledger.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.ziji.ledger.domain.Money;
import app.ziji.shared.application.TransactionRunner;

/** 从不可变 LedgerEntry 事实全量重建账户余额快照。 */
public final class BalanceProjectionService {

	private final TransactionRunner transactions;
	private final BalanceProjectionStore snapshots;
	private final Clock clock;

	public BalanceProjectionService(TransactionRunner transactions, BalanceProjectionStore snapshots, Clock clock) {
		if (transactions == null || snapshots == null || clock == null) {
			throw new IllegalArgumentException("余额投影重建依赖不能为空。");
		}
		this.transactions = transactions;
		this.snapshots = snapshots;
		this.clock = clock;
	}

	public BalanceProjectionRebuildResult rebuildAll() {
		return transactions.required(() -> {
			List<AccountBalanceSnapshot> expected = snapshots.aggregatePostedEntries();
			Map<SnapshotKey, Money> expectedBalances = index(expected);
			int previousDifferences = differences(expectedBalances, index(snapshots.readSnapshots()));

			// 删除和全量写入共用同一事务；任一步失败都不能留下半完成投影。
			snapshots.replaceSnapshots(expected, clock.instant());
			int remainingDifferences = differences(expectedBalances, index(snapshots.readSnapshots()));
			if (remainingDifferences != 0) {
				throw new LedgerPersistenceException(new IllegalStateException("余额投影重建后与事实不一致。"));
			}
			return new BalanceProjectionRebuildResult(expected.size(), previousDifferences, remainingDifferences);
		});
	}

	private static Map<SnapshotKey, Money> index(List<AccountBalanceSnapshot> snapshots) {
		if (snapshots == null) {
			throw new LedgerPersistenceException(new IllegalStateException("余额投影读取失败。"));
		}
		Map<SnapshotKey, Money> indexed = new HashMap<>();
		for (AccountBalanceSnapshot snapshot : snapshots) {
			if (snapshot == null) {
				throw new LedgerPersistenceException(new IllegalStateException("余额投影记录无效。"));
			}
			SnapshotKey key = new SnapshotKey(snapshot.ledgerAccountId(), snapshot.businessDate());
			if (indexed.putIfAbsent(key, snapshot.balance()) != null) {
				throw new LedgerPersistenceException(new IllegalStateException("余额投影键重复。"));
			}
		}
		return indexed;
	}

	private static int differences(Map<SnapshotKey, Money> expected, Map<SnapshotKey, Money> actual) {
		Set<SnapshotKey> keys = new HashSet<>(expected.keySet());
		keys.addAll(actual.keySet());
		return (int) keys.stream().filter(key -> !java.util.Objects.equals(expected.get(key), actual.get(key))).count();
	}

	private record SnapshotKey(UUID ledgerAccountId, java.time.LocalDate businessDate) {
	}
}
