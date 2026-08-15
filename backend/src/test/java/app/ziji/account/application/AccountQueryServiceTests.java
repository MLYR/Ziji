package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountDomainException;
import app.ziji.account.domain.AccountPatch;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账户查询服务在纯 Java 下的稳定分页、可见性、权限和乐观锁编排测试。 */
class AccountQueryServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
	private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000603");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");
	private static final Instant FIXED_NOW = Instant.parse("2026-08-15T03:04:05Z");

	@Test
	void listAppliesDefaultAndMaximumLimits() {
		FakeReadPort reads = new FakeReadPort();
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		for (int index = 0; index < 55; index++) {
			UUID accountId = accountId(index);
			reads.add(account(accountId, Instant.ofEpochSecond(1_000 + index), 1));
			memberships.add(new ActiveMembership(accountId, "OWNER", BigDecimal.ONE));
		}
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		AccountPage defaultPage = service.listVisibleAccounts(USER_ID, null, null);
		assertEquals(50, defaultPage.accounts().size());
		assertTrue(defaultPage.hasMore());
		assertTrue(defaultPage.nextCursor() != null);

		AccountPage maximumPage = service.listVisibleAccounts(USER_ID, 200, null);
		assertEquals(55, maximumPage.accounts().size());
		assertFalse(maximumPage.hasMore());
		assertEquals(null, maximumPage.nextCursor());
	}

	@Test
	void listRejectsInvalidLimitsAndNullUser() {
		AccountQueryService service = service(new FakeReadPort(), new FakeUpdatePort(null), new FakeMembershipReadPort());

		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(null, null, null));
		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 0, null));
		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 201, null));
	}

	@Test
	void listReturnsEmptyPageWhenUserHasNoActiveMemberships() {
		AccountQueryService service = service(new FakeReadPort(), new FakeUpdatePort(null), new FakeMembershipReadPort());

		AccountPage page = service.listVisibleAccounts(USER_ID, null, null);

		assertEquals(List.of(), page.accounts());
		assertFalse(page.hasMore());
		assertEquals(null, page.nextCursor());
	}

	@Test
	void listPagesWithoutDuplicatesOrOmissionsAndStableOrder() {
		FakeReadPort reads = new FakeReadPort();
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		List<Account> seeded = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			Instant createdAt = Instant.ofEpochSecond(1_000 + index);
			Account account = account(accountId(index), createdAt, 1);
			reads.add(account);
			memberships.add(new ActiveMembership(account.id(), "VIEWER", new BigDecimal("0.500000")));
			seeded.add(account);
		}
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		List<UUID> collected = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		while (true) {
			AccountPage page = service.listVisibleAccounts(USER_ID, 2, cursor);
			page.accounts().forEach(account -> collected.add(account.id()));
			pages++;
			if (!page.hasMore()) {
				break;
			}
			cursor = page.nextCursor();
		}

		assertEquals(3, pages);
		assertEquals(5, collected.size());
		assertEquals(5, collected.stream().distinct().count());
		Comparator<Account> stableOrder = (left, right) -> {
			int byCreatedAt = right.createdAt().compareTo(left.createdAt());
			if (byCreatedAt != 0) {
				return byCreatedAt;
			}
			return right.id().compareTo(left.id());
		};
		List<UUID> expected = seeded.stream()
			.sorted(stableOrder)
			.map(Account::id)
			.toList();
		assertEquals(expected, collected);
	}

	@Test
	void listOrdersAccountsWithSameCreatedAtByIdDescending() {
		Instant sameCreatedAt = Instant.ofEpochSecond(2_000);
		FakeReadPort reads = new FakeReadPort();
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		List<Account> seeded = new ArrayList<>();
		for (int index = 0; index < 3; index++) {
			Account account = account(accountId(index), sameCreatedAt, 1);
			reads.add(account);
			memberships.add(new ActiveMembership(account.id(), "EDITOR", BigDecimal.ZERO));
			seeded.add(account);
		}
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		AccountPage page = service.listVisibleAccounts(USER_ID, 10, null);

		assertEquals(
			seeded.stream().map(Account::id).sorted(Comparator.reverseOrder()).toList(),
			page.accounts().stream().map(AccountQueryResult::id).toList());
	}

	@Test
	void listRejectsTamperedAndForeignCursor() {
		FakeReadPort reads = new FakeReadPort();
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		Account account = account(ACCOUNT_ID, Instant.ofEpochSecond(3_000), 1);
		reads.add(account);
		memberships.add(new ActiveMembership(account.id(), "OWNER", BigDecimal.ONE));
		Account second = account(accountId(1), Instant.ofEpochSecond(3_001), 1);
		reads.add(second);
		memberships.add(new ActiveMembership(second.id(), "OWNER", BigDecimal.ONE));
		FakeCursorCodec sharedCursors = new FakeCursorCodec();
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships, sharedCursors);

		AccountPage first = service.listVisibleAccounts(USER_ID, 1, null);
		String cursor = first.nextCursor();
		assertTrue(cursor != null);

		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 1, "not-a-valid-cursor"));
		String tampered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A");
		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 1, tampered));
		// 游标对应的账户不再属于当前 ACTIVE membership 过滤结果时，不能继续翻页。
		memberships.remove(second.id());
		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 1, cursor));
		memberships.add(new ActiveMembership(second.id(), "OWNER", BigDecimal.ONE));

		// 另一个用户可见账户的游标不能跨用户复用。
		Account foreign = account(accountId(99), Instant.ofEpochSecond(4_000), 1);
		Account foreignSecond = account(accountId(100), Instant.ofEpochSecond(4_001), 1);
		FakeReadPort foreignReads = new FakeReadPort();
		foreignReads.add(foreign);
		foreignReads.add(foreignSecond);
		FakeMembershipReadPort foreignMemberships = new FakeMembershipReadPort();
		foreignMemberships.add(new ActiveMembership(foreign.id(), "OWNER", BigDecimal.ONE));
		foreignMemberships.add(new ActiveMembership(foreignSecond.id(), "OWNER", BigDecimal.ONE));
		foreignMemberships.moveAll(USER_ID, OTHER_USER_ID);
		AccountQueryService foreignService = service(
			foreignReads, new FakeUpdatePort(foreignReads), foreignMemberships, sharedCursors);
		String foreignCursor = foreignService.listVisibleAccounts(OTHER_USER_ID, 1, null).nextCursor();
		assertThrows(AccountQueryValidationException.class,
			() -> service.listVisibleAccounts(USER_ID, 1, foreignCursor));
	}

	@Test
	void getVisibleReturnsCompositeOrThrowsWhenInvisible() {
		FakeReadPort reads = new FakeReadPort();
		reads.add(account(ACCOUNT_ID, FIXED_NOW, 7));
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		memberships.add(new ActiveMembership(ACCOUNT_ID, "VIEWER", new BigDecimal("0.700000")));
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		AccountQueryResult result = service.getVisibleAccount(USER_ID, ACCOUNT_ID);
		assertEquals("VIEWER", result.currentUserRole());
		assertEquals(0, result.inclusionRatio().compareTo(new BigDecimal("0.700000")));
		assertEquals(7, result.version());
		assertEquals("\"7\"", result.etag());

		assertThrows(AccountNotVisibleException.class,
			() -> service.getVisibleAccount(USER_ID, UUID.randomUUID()));
	}

	@Test
	void updateRequiresOwnerRoleAndRejectsStaleVersion() {
		FakeReadPort reads = new FakeReadPort();
		reads.add(account(ACCOUNT_ID, FIXED_NOW, 4));
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		memberships.add(new ActiveMembership(ACCOUNT_ID, "VIEWER", BigDecimal.ONE));
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		assertThrows(AccountPermissionDeniedException.class,
			() -> service.updateAccount(USER_ID, ACCOUNT_ID, 4, new AccountPatch(true, "新名称", false, null)));

		memberships.replace(ACCOUNT_ID, new ActiveMembership(ACCOUNT_ID, "OWNER", BigDecimal.ONE));
		AccountQueryResult updated = service.updateAccount(USER_ID, ACCOUNT_ID, 4,
			new AccountPatch(true, "新名称", false, null));
		assertEquals("新名称", updated.name());
		assertEquals(5, updated.version());
		assertEquals("\"5\"", updated.etag());

		assertThrows(AccountVersionConflictException.class,
			() -> service.updateAccount(USER_ID, ACCOUNT_ID, 3, new AccountPatch(true, "过期", false, null)));
	}

	@Test
	void updateRejectsInvalidPatchAndMissingMembership() {
		FakeReadPort reads = new FakeReadPort();
		reads.add(account(ACCOUNT_ID, FIXED_NOW, 4));
		FakeMembershipReadPort memberships = new FakeMembershipReadPort();
		memberships.add(new ActiveMembership(ACCOUNT_ID, "OWNER", BigDecimal.ONE));
		AccountQueryService service = service(reads, new FakeUpdatePort(reads), memberships);

		assertThrows(AccountQueryValidationException.class,
			() -> service.updateAccount(null, ACCOUNT_ID, 4, new AccountPatch(true, "名称", false, null)));
		assertThrows(AccountQueryValidationException.class,
			() -> service.updateAccount(USER_ID, ACCOUNT_ID, 0, new AccountPatch(true, "名称", false, null)));
		assertThrows(AccountDomainException.class,
			() -> service.updateAccount(USER_ID, ACCOUNT_ID, 4, new AccountPatch(true, "A".repeat(101), false, null)));
		assertThrows(AccountNotVisibleException.class,
			() -> service.updateAccount(USER_ID, UUID.randomUUID(), 4, new AccountPatch(true, "名称", false, null)));
	}

	private static AccountQueryService service(
		AccountQueryReadPort reads,
		AccountUpdatePort updates,
		AccountMembershipReadPort memberships) {
		return service(reads, updates, memberships, new FakeCursorCodec());
	}

	private static AccountQueryService service(
		AccountQueryReadPort reads,
		AccountUpdatePort updates,
		AccountMembershipReadPort memberships,
		AccountCursorCodec cursors) {
		return new AccountQueryService(reads, updates, memberships, cursors, new DirectTransactionRunner(),
			Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private static Account account(UUID accountId, Instant createdAt, int version) {
		return Account.restore(
			accountId, AccountClass.ASSET, AccountType.BANK, "账户-" + accountId.toString().substring(0, 8),
			"机构-" + accountId.toString().substring(0, 8), AccountCurrency.CNY, "备注",
			AccountStatus.ACTIVE, null, USER_ID, createdAt, createdAt, version);
	}

	private static UUID accountId(int index) {
		return new UUID(0, index);
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class FakeReadPort implements AccountQueryReadPort {
		private final Map<UUID, Account> accounts = new LinkedHashMap<>();

		private void add(Account account) {
			accounts.put(account.id(), account);
		}

		@Override
		public Optional<Account> findById(UUID accountId) {
			return Optional.ofNullable(accounts.get(accountId));
		}

		@Override
		public List<Account> listByIds(
			Collection<UUID> accountIds,
			AccountKeysetPosition after,
			int maximumRecords) {
			Comparator<Account> stableOrder = (left, right) -> {
				int byCreatedAt = right.createdAt().compareTo(left.createdAt());
				if (byCreatedAt != 0) {
					return byCreatedAt;
				}
				return right.id().compareTo(left.id());
			};
			return accounts.values().stream()
				.filter(account -> accountIds.contains(account.id()))
				.filter(account -> after == null || follows(account, after))
				.sorted(stableOrder)
				.limit(maximumRecords)
				.toList();
		}

		private static boolean follows(Account account, AccountKeysetPosition after) {
			int byCreatedAt = account.createdAt().compareTo(after.createdAt());
			return byCreatedAt < 0 || (byCreatedAt == 0 && account.id().compareTo(after.accountId()) < 0);
		}
	}

	private static final class FakeUpdatePort implements AccountUpdatePort {
		private final FakeReadPort reads;

		private FakeUpdatePort(FakeReadPort reads) {
			this.reads = reads;
		}

		@Override
		public Optional<Account> updateIfVersion(
			UUID accountId, int expectedVersion, AccountPatch patch, Instant updatedAt) {
			Account current = reads.findById(accountId).orElse(null);
			if (current == null || current.version() != expectedVersion) {
				return Optional.empty();
			}
			Account updated = current.apply(patch, updatedAt);
			reads.accounts.put(accountId, updated);
			return Optional.of(updated);
		}
	}

	private static final class FakeMembershipReadPort implements AccountMembershipReadPort {
		private final Map<UUID, Map<UUID, ActiveMembership>> membershipsByUser = new HashMap<>();

		private void add(ActiveMembership membership) {
			add(USER_ID, membership);
		}

		private void add(UUID userId, ActiveMembership membership) {
			membershipsByUser.computeIfAbsent(userId, ignored -> new HashMap<>())
				.put(membership.accountId(), membership);
		}

		private void replace(UUID accountId, ActiveMembership membership) {
			add(USER_ID, membership);
		}

		private void moveAll(UUID fromUserId, UUID toUserId) {
			membershipsByUser.put(toUserId, membershipsByUser.remove(fromUserId));
		}

		private void remove(UUID accountId) {
			membershipsByUser.getOrDefault(USER_ID, Map.of()).remove(accountId);
		}

		@Override
		public List<ActiveMembership> listActiveMemberships(UUID userId) {
			return List.copyOf(membershipsByUser.getOrDefault(userId, Map.of()).values());
		}

		@Override
		public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
			return Optional.ofNullable(membershipsByUser.getOrDefault(userId, Map.of()).get(accountId));
		}
	}

	private static final class FakeCursorCodec implements AccountCursorCodec {
		private final Map<String, CursorEntry> entries = new HashMap<>();
		private int next = 1;

		@Override
		public String encode(UUID userId, AccountKeysetPosition position) {
			String cursor = "cursor-" + next++;
			entries.put(cursor, new CursorEntry(userId, position));
			return cursor;
		}

		@Override
		public AccountKeysetPosition decode(UUID userId, String cursor) {
			CursorEntry entry = entries.get(cursor);
			if (entry == null || !entry.userId().equals(userId)) {
				throw new AccountQueryValidationException();
			}
			return entry.position();
		}

		private record CursorEntry(UUID userId, AccountKeysetPosition position) {
		}
	}
}
