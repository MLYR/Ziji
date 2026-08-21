package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BE-ACC-005 的归档余额确认、权限、版本和历史保留应用边界测试。 */
class AccountArchiveServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final Instant NOW = Instant.parse("2026-08-21T05:06:07Z");

	@Test
	void zeroBalanceArchivesWithEitherConfirmationValueAndKeepsVersionHistory() {
		Fixture fixture = fixture(BigDecimal.ZERO, "OWNER");

		AccountQueryResult archived = fixture.service().archive(
			USER_ID, ACCOUNT_ID, 1, "账户已完成清理", false, "request-zero-001");

		assertEquals(AccountStatus.ARCHIVED, archived.status());
		assertEquals(2, archived.version());
		assertEquals("\"2\"", archived.etag());
		assertEquals(AccountStatus.ARCHIVED, fixture.accounts().findById(ACCOUNT_ID).orElseThrow().status());
		assertEquals(1, fixture.audits().entries.size());
		assertEquals("ACCOUNT_ARCHIVED", fixture.audits().entries.getFirst().action());
		assertFalse(fixture.audits().entries.getFirst().metadata().containsKey("reason"));
		assertFalse(fixture.audits().entries.getFirst().metadata().containsKey("balance"));

		Fixture second = fixture(BigDecimal.ZERO, "OWNER");
		AccountQueryResult secondArchived = second.service().archive(
			USER_ID, ACCOUNT_ID, 1, "账户已完成清理", true, "request-zero-002");
		assertEquals(AccountStatus.ARCHIVED, secondArchived.status());
	}

	@Test
	void nonZeroBalanceRequiresExplicitConfirmationWithoutWritingFacts() {
		Fixture fixture = fixture(new BigDecimal("12.34"), "OWNER");

		assertThrows(AccountArchiveException.NonZeroBalanceConfirmationRequired.class,
			() -> fixture.service().archive(USER_ID, ACCOUNT_ID, 1, "账户已完成清理", false, "request-nonzero-01"));

		assertEquals(AccountStatus.ACTIVE, fixture.accounts().findById(ACCOUNT_ID).orElseThrow().status());
		assertEquals(1, fixture.accounts().findById(ACCOUNT_ID).orElseThrow().version());
		assertTrue(fixture.audits().entries.isEmpty());
		assertEquals(1, fixture.balances().reads);

		AccountQueryResult archived = fixture.service().archive(
			USER_ID, ACCOUNT_ID, 1, "账户已完成清理", true, "request-nonzero-02");
		assertEquals(AccountStatus.ARCHIVED, archived.status());
		assertEquals(1, fixture.audits().entries.size());
	}

	@Test
	void onlyCurrentOwnerCanArchiveAndInvisibleMembershipDoesNotLeak() {
		Fixture editor = fixture(BigDecimal.ZERO, "EDITOR");
		assertThrows(AccountPermissionDeniedException.class,
			() -> editor.service().archive(USER_ID, ACCOUNT_ID, 1, "清理", true, "request-editor-01"));

		Fixture removed = fixture(BigDecimal.ZERO, null);
		assertThrows(AccountNotVisibleException.class,
			() -> removed.service().archive(USER_ID, ACCOUNT_ID, 1, "清理", true, "request-removed-01"));
	}

	@Test
	void staleVersionAndArchivedReplayFailClosed() {
		Fixture fixture = fixture(BigDecimal.ZERO, "OWNER");
		fixture.accounts().archiveIfVersion(ACCOUNT_ID, 1, NOW);

		assertThrows(AccountArchiveException.AlreadyArchived.class,
			() -> fixture.service().archive(USER_ID, ACCOUNT_ID, 1, "清理", true, "request-archived-01"));
		assertEquals(2, fixture.service().replay(USER_ID, ACCOUNT_ID, 2).version());
		assertThrows(AccountArchiveException.SafeReplayUnavailable.class,
			() -> fixture.service().replay(USER_ID, ACCOUNT_ID, 1));

		Fixture stale = fixture(BigDecimal.ZERO, "OWNER");
		stale.accounts().bumpVersion(ACCOUNT_ID);
		AccountVersionConflictException conflict = assertThrows(AccountVersionConflictException.class,
			() -> stale.service().archive(USER_ID, ACCOUNT_ID, 1, "清理", true, "request-stale-01"));
		assertEquals(2, conflict.current().version());
	}

	private static Fixture fixture(BigDecimal balance, String role) {
		FakeAccounts accounts = new FakeAccounts();
		accounts.put(account());
		FakeMemberships memberships = new FakeMemberships();
		if (role != null) {
			memberships.membership = new ActiveMembership(ACCOUNT_ID, role, BigDecimal.ONE);
		}
		FakeBalances balances = new FakeBalances(balance);
		FakeAudits audits = new FakeAudits();
		AccountArchiveService service = new AccountArchiveService(
			new DirectTransactions(), accounts, accounts, memberships, balances, audits,
			Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(service, accounts, balances, audits);
	}

	private static Account account() {
		return Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "归档账户", "机构",
			AccountCurrency.CNY, "历史备注", AccountStatus.ACTIVE, null, USER_ID,
			NOW.minusSeconds(60), NOW.minusSeconds(60), 1);
	}

	private record Fixture(
		AccountArchiveService service,
		FakeAccounts accounts,
		FakeBalances balances,
		FakeAudits audits) {
	}

	private static final class DirectTransactions implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class FakeAccounts implements AccountStore, AccountArchiveStore {
		private final Map<UUID, Account> accounts = new HashMap<>();

		private void put(Account account) {
			accounts.put(account.id(), account);
		}

		@Override
		public void insert(Account account) {
			put(account);
		}

		@Override
		public Optional<Account> findById(UUID accountId) {
			return Optional.ofNullable(accounts.get(accountId));
		}

		@Override
		public Optional<Account> findByIdForUpdate(UUID accountId) {
			return findById(accountId);
		}

		@Override
		public Optional<Account> archiveIfVersion(UUID accountId, int expectedVersion, Instant archivedAt) {
			Account current = accounts.get(accountId);
			if (current == null || current.status() != AccountStatus.ACTIVE || current.version() != expectedVersion) {
				return Optional.empty();
			}
			Account archived = current.archive(archivedAt);
			accounts.put(accountId, archived);
			return Optional.of(archived);
		}

		private void bumpVersion(UUID accountId) {
			Account current = accounts.get(accountId);
			accounts.put(accountId, Account.restore(
				current.id(), current.accountClass(), current.accountType(), current.name(), current.institution(),
				current.currency(), current.note(), current.status(), current.archivedAt(), current.createdBy(),
				current.createdAt(), NOW, current.version() + 1));
		}
	}

	private static final class FakeMemberships implements AccountMembershipReadPort {
		private ActiveMembership membership;

		@Override
		public java.util.List<ActiveMembership> listActiveMemberships(UUID userId) {
			return membership == null ? java.util.List.of() : java.util.List.of(membership);
		}

		@Override
		public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
			return membership != null && membership.accountId().equals(accountId)
				? Optional.of(membership) : Optional.empty();
		}

		@Override
		public Optional<ActiveMembership> findActiveMembershipForUpdate(UUID userId, UUID accountId) {
			return findActiveMembership(userId, accountId);
		}
	}

	private static final class FakeBalances implements AccountBalanceReadPort {
		private final PostedPrimaryBalance balance;
		private int reads;

		private FakeBalances(BigDecimal amount) {
			this.balance = new PostedPrimaryBalance(amount, AccountCurrency.CNY);
		}

		@Override
		public Optional<PostedPrimaryBalance> findPostedPrimaryBalance(UUID accountId) {
			reads++;
			return Optional.of(balance);
		}
	}

	private static final class FakeAudits implements AuditLogWritePort {
		private final ArrayList<AuditLogEntry> entries = new ArrayList<>();

		@Override
		public void append(AuditLogEntry entry) {
			entries.add(entry);
		}
	}
}
