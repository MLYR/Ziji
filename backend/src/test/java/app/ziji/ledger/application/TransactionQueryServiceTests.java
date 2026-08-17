package app.ziji.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.category.application.CategoryReference;
import app.ziji.category.application.CategoryStore;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionStatus;
import app.ziji.ledger.domain.TransactionType;
import org.junit.jupiter.api.Test;

/** 交易读取 application seam 的筛选、稳定 keyset 和 ACTIVE membership fail-closed 测试。 */
class TransactionQueryServiceTests {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000701");
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000702");
	private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000703");

	@Test
	void listPagesStableByDateAndIdWithoutDuplicates() {
		FakeReadPort reads = new FakeReadPort();
		FakeMemberships memberships = new FakeMemberships();
		memberships.memberships.add(new AccountMembershipReadPort.ActiveMembership(ACCOUNT, "VIEWER", BigDecimal.ONE));
		for (int i = 0; i < 5; i++) {
			reads.add(snapshot(UUID.randomUUID(), LocalDate.of(2026, 8, 15 - (i / 2)), ACCOUNT));
		}
		TransactionQueryService service = service(reads, memberships, new FakeCategories());
		List<UUID> ids = new ArrayList<>();
		String cursor = null;
		do {
			TransactionPage page = service.list(USER, new TransactionQuery(null, null, null, null, null), 2, cursor);
			page.transactions().forEach(row -> ids.add(row.transaction().transactionId()));
			cursor = page.nextCursor();
			if (!page.hasMore()) {
				break;
			}
		} while (true);
		assertEquals(5, ids.size());
		assertEquals(5, ids.stream().distinct().count());
	}

	@Test
	void rejectsInvalidDateRangeAndForeignOrTamperedCursor() {
		FakeReadPort reads = new FakeReadPort();
		FakeMemberships memberships = new FakeMemberships();
		memberships.memberships.add(new AccountMembershipReadPort.ActiveMembership(ACCOUNT, "OWNER", BigDecimal.ONE));
		reads.add(snapshot(UUID.randomUUID(), LocalDate.of(2026, 8, 15), ACCOUNT));
		reads.add(snapshot(UUID.randomUUID(), LocalDate.of(2026, 8, 14), ACCOUNT));
		TransactionQueryService service = service(reads, memberships, new FakeCategories());
		assertThrows(TransactionQueryValidationException.class, () -> service.list(USER,
			new TransactionQuery(null, null, LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 15), null), 10, null));
		TransactionPage first = service.list(USER, new TransactionQuery(null, null, null, null, null), 1, null);
		assertTrue(first.nextCursor() != null);
		assertThrows(TransactionNotVisibleException.class, () -> service.list(USER,
			new TransactionQuery(OTHER_ACCOUNT, null, null, null, null), 1, first.nextCursor()));
		assertThrows(TransactionQueryValidationException.class, () -> service.list(USER,
			new TransactionQuery(null, null, null, null, null), 1, "tampered"));
	}

	@Test
	void removedMembershipCannotReadDetail() {
		FakeReadPort reads = new FakeReadPort();
		FakeMemberships memberships = new FakeMemberships();
		UUID transactionId = UUID.randomUUID();
		reads.add(snapshot(transactionId, LocalDate.of(2026, 8, 15), ACCOUNT));
		TransactionQueryService service = service(reads, memberships, new FakeCategories());
		assertThrows(TransactionNotVisibleException.class, () -> service.get(USER, transactionId));
		assertFalse(service.list(USER, new TransactionQuery(null, null, null, null, null), 50, null).hasMore());
	}

	private TransactionQueryService service(FakeReadPort reads, FakeMemberships memberships, FakeCategories categories) {
		return new TransactionQueryService(reads, memberships, categories,
			new app.ziji.ledger.infrastructure.AesGcmTransactionCursorCodec(
				new byte[32], new java.security.SecureRandom()));
	}

	private static TransactionQueryReadPort.TransactionSnapshot snapshot(UUID id, LocalDate date, UUID accountId) {
		LedgerEntry entry = new LedgerEntry(UUID.randomUUID(), id, UUID.randomUUID(), 1, LedgerDirection.DEBIT,
			new Money(new BigDecimal("1.00"), CurrencyCode.CNY), date);
		return new TransactionQueryReadPort.TransactionSnapshot(new Transaction(id, TransactionType.EXPENSE,
			TransactionStatus.POSTED, date.atStartOfDay(ZoneId.of("UTC")).toInstant(), date, "UTC",
			TransactionSource.MANUAL, id, null, null, 1, Instant.now(), List.of(entry)), 1);
	}

	private static final class FakeReadPort implements TransactionQueryReadPort {
		private final List<TransactionSnapshot> rows = new ArrayList<>();
		void add(TransactionSnapshot row) { rows.add(row); }
		@Override public List<TransactionSnapshot> listVisible(Set<UUID> ids, TransactionQuery query, TransactionKeysetPosition after, int max) {
			return rows.stream().filter(row -> after == null || before(row.transaction(), after)).sorted((a, b) -> {
				int date = b.transaction().businessDate().compareTo(a.transaction().businessDate());
				return date != 0 ? date : b.transaction().transactionId().compareTo(a.transaction().transactionId());
			}).limit(max).toList();
		}
		@Override public boolean hasVisibleBoundary(Set<UUID> ids, TransactionQuery query, TransactionKeysetPosition position) {
			return rows.stream().anyMatch(row -> row.transaction().businessDate().equals(position.businessDate())
				&& row.transaction().transactionId().equals(position.transactionId()));
		}
		@Override public Optional<TransactionSnapshot> findVisible(Set<UUID> ids, UUID id) {
			return ids.isEmpty() ? Optional.empty() : rows.stream().filter(row -> row.transaction().transactionId().equals(id)).findFirst();
		}
		private static boolean before(Transaction tx, TransactionKeysetPosition after) {
			return tx.businessDate().isBefore(after.businessDate())
				|| tx.businessDate().equals(after.businessDate()) && tx.transactionId().compareTo(after.transactionId()) < 0;
		}
	}

	private static final class FakeMemberships implements AccountMembershipReadPort {
		private final List<ActiveMembership> memberships = new ArrayList<>();
		@Override public List<ActiveMembership> listActiveMemberships(UUID userId) { return List.copyOf(memberships); }
		@Override public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
			return memberships.stream().filter(m -> m.accountId().equals(accountId)).findFirst();
		}
	}

	private static final class FakeCategories implements CategoryStore {
		private final Map<UUID, CategoryReference> values = new HashMap<>();
		@Override public Optional<CategoryReference> findById(UUID categoryId) { return Optional.ofNullable(values.get(categoryId)); }
	}
}
