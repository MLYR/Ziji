package app.ziji.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

/** 全量重建只以事实汇总为准，并在写后确认差异归零。 */
class BalanceProjectionServiceTests {

	private static final UUID LEDGER_ACCOUNT_ID = UUID.randomUUID();
	private static final LocalDate FIRST_DATE = LocalDate.of(2026, 8, 10);
	private static final LocalDate SECOND_DATE = LocalDate.of(2026, 8, 11);

	@Test
	void replacesStaleSnapshotsAndReportsZeroRemainingDifferences() {
		FakeStore store = new FakeStore(List.of(snapshot(FIRST_DATE, "10.00")),
			List.of(snapshot(FIRST_DATE, "9.00"), snapshot(SECOND_DATE, "99.00")), false);
		BalanceProjectionService service = new BalanceProjectionService(new ImmediateTransactions(), store,
			Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

		BalanceProjectionRebuildResult result = service.rebuildAll();

		assertEquals(1, result.snapshotCount());
		assertEquals(2, result.previousDifferenceCount());
		assertEquals(0, result.differenceCount());
		assertEquals(List.of(snapshot(FIRST_DATE, "10.00")), store.current);
	}

	@Test
	void rejectsARebuildWhoseStoredProjectionStillDiffersFromFacts() {
		FakeStore store = new FakeStore(List.of(snapshot(FIRST_DATE, "10.00")),
			List.of(snapshot(FIRST_DATE, "9.00")), true);
		BalanceProjectionService service = new BalanceProjectionService(new ImmediateTransactions(), store,
			Clock.systemUTC());

		assertThrows(LedgerPersistenceException.class, service::rebuildAll);
	}

	private static AccountBalanceSnapshot snapshot(LocalDate date, String amount) {
		return new AccountBalanceSnapshot(LEDGER_ACCOUNT_ID, date,
			new Money(new BigDecimal(amount), CurrencyCode.CNY));
	}

	private static final class FakeStore implements BalanceProjectionStore {
		private final List<AccountBalanceSnapshot> facts;
		private final boolean preserveStaleProjection;
		private List<AccountBalanceSnapshot> current;

		private FakeStore(
			List<AccountBalanceSnapshot> facts,
			List<AccountBalanceSnapshot> current,
			boolean preserveStaleProjection) {
			this.facts = List.copyOf(facts);
			this.current = new ArrayList<>(current);
			this.preserveStaleProjection = preserveStaleProjection;
		}

		@Override
		public List<AccountBalanceSnapshot> aggregatePostedEntries() {
			return facts;
		}

		@Override
		public List<AccountBalanceSnapshot> readSnapshots() {
			return List.copyOf(current);
		}

		@Override
		public void replaceSnapshots(List<AccountBalanceSnapshot> snapshots, Instant calculatedAt) {
			if (!preserveStaleProjection) {
				current = new ArrayList<>(snapshots);
			}
		}
	}

	private static final class ImmediateTransactions implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}
}
