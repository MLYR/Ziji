package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.account.application.LiquidityHoldBalanceReadPort.EffectiveHoldAmounts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 余额用例在公开 application 端口上的权限、金额、时点和事实完整性测试。 */
class AccountBalanceServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03Z");
	private static final Instant EXPLICIT_AS_OF = Instant.parse("2026-08-16T04:05:06Z");

	@Test
	void ownerEditorViewerCanReadTheSameFactBalance() {
		for (String role : List.of("OWNER", "EDITOR", "VIEWER")) {
			Fixture fixture = fixture(role, new BigDecimal("100.00"),
				new EffectiveHoldAmounts(1, "CNY", new BigDecimal("5.00"), new BigDecimal("7.00"), new BigDecimal("3.00")));

			AccountBalanceResult result = fixture.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF);

			assertEquals(ACCOUNT_ID, result.accountId());
			assertEquals(AccountCurrency.CNY, result.currency());
			assertEquals(new BigDecimal("100.00"), result.ledgerBalance());
			assertEquals(new BigDecimal("15.00"), result.unavailableAmount());
			assertEquals(new BigDecimal("5.00"), result.unavailableBreakdown().frozen());
			assertEquals(new BigDecimal("7.00"), result.unavailableBreakdown().inTransit());
			assertEquals(new BigDecimal("3.00"), result.unavailableBreakdown().reserved());
			assertEquals(new BigDecimal("85.00"), result.availableBalance());
			assertEquals(AccountBalanceResult.LiquidityStatus.NORMAL, result.liquidityStatus());
			assertEquals(EXPLICIT_AS_OF, result.asOf());
			assertEquals(0, result.asOfSequence());
			assertEquals(EXPLICIT_AS_OF, fixture.ledger().asOf);
			assertEquals(EXPLICIT_AS_OF, fixture.holds().asOf);
		}
	}

	@Test
	void missingActiveMembershipIsNotVisibleBeforeReadingAccountFacts() {
		Fixture fixture = fixture(null, new BigDecimal("100.00"),
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

		assertThrows(AccountNotVisibleException.class,
			() -> fixture.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));
		assertEquals(0, fixture.ledger().calls);
		assertEquals(0, fixture.holds().calls);
	}

	@Test
	void negativeAvailableBalanceIsReturnedWithoutClamping() {
		Fixture fixture = fixture("VIEWER", new BigDecimal("10.00"),
			new EffectiveHoldAmounts(1, "CNY", new BigDecimal("11.00"), BigDecimal.ZERO, BigDecimal.ZERO));

		AccountBalanceResult result = fixture.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF);

		assertEquals(new BigDecimal("-1.00"), result.availableBalance());
		assertEquals(AccountBalanceResult.LiquidityStatus.NEGATIVE_AVAILABLE, result.liquidityStatus());
	}

	@Test
	void explicitAsOfDoesNotReadClockAndMissingAsOfReadsItOnceInsideSnapshot() {
		CountingClock clock = new CountingClock(EXPLICIT_AS_OF);
		Fixture fixture = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), clock);

		AccountBalanceResult explicit = fixture.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF);
		assertEquals(EXPLICIT_AS_OF, explicit.asOf());
		assertEquals(0, clock.calls);

		AccountBalanceResult captured = fixture.service().getBalance(USER_ID, ACCOUNT_ID, null);
		assertEquals(EXPLICIT_AS_OF, captured.asOf());
		assertEquals(1, clock.calls);
	}

	@Test
	void primaryMissingOrCurrencyMismatchFailsClosed() {
		Fixture missingPrimary = fixture("OWNER", null,
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> missingPrimary.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));

		Fixture mismatchedPrimary = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		mismatchedPrimary.ledger().balance = new AccountBalanceReadPort.PostedPrimaryBalance(
			new BigDecimal("100.00"), "USD");
		assertThrows(AccountBalanceException.class,
			() -> mismatchedPrimary.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));
	}

	@Test
	void mismatchedHoldCurrencyAndPrecisionErrorsFailClosed() {
		Fixture mismatchedCurrency = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(1, "USD", new BigDecimal("5.00"), BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> mismatchedCurrency.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));

		Fixture invalidLedgerPrecision = fixture("OWNER", new BigDecimal("100.001"),
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> invalidLedgerPrecision.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));

		Fixture invalidHoldPrecision = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(1, "CNY", new BigDecimal("5.001"), BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> invalidHoldPrecision.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));

		Fixture invalidHoldFact = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(1, "CNY", new BigDecimal("3.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1));
		assertThrows(AccountBalanceException.class,
			() -> invalidHoldFact.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));
	}

	@Test
	void invalidMembershipAndHoldAggregationFailClosed() {
		Fixture wrongAccountMembership = fixtureWithMembership(new ActiveMembership(UUID.randomUUID(), "OWNER", BigDecimal.ONE),
			new BigDecimal("100.00"),
			new EffectiveHoldAmounts(0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> wrongAccountMembership.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));

		Fixture multipleHoldCurrencies = fixture("OWNER", new BigDecimal("100.00"),
			new EffectiveHoldAmounts(2, "CNY", new BigDecimal("5.00"), BigDecimal.ZERO, BigDecimal.ZERO));
		assertThrows(AccountBalanceException.class,
			() -> multipleHoldCurrencies.service().getBalance(USER_ID, ACCOUNT_ID, EXPLICIT_AS_OF));
	}

	private Fixture fixture(String role, BigDecimal ledgerAmount, EffectiveHoldAmounts holds) {
		return fixture(role, ledgerAmount, holds, Clock.fixed(EXPLICIT_AS_OF, ZoneOffset.UTC));
	}

	private Fixture fixture(
		String role, BigDecimal ledgerAmount, EffectiveHoldAmounts holds, Clock clock) {
		return fixtureWithMembership(role == null ? null : new ActiveMembership(ACCOUNT_ID, role, BigDecimal.ONE),
			ledgerAmount, holds, clock);
	}

	private Fixture fixtureWithMembership(ActiveMembership membership, BigDecimal ledgerAmount, EffectiveHoldAmounts holds) {
		return fixtureWithMembership(membership, ledgerAmount, holds, Clock.fixed(EXPLICIT_AS_OF, ZoneOffset.UTC));
	}

	private Fixture fixtureWithMembership(
		ActiveMembership membership, BigDecimal ledgerAmount, EffectiveHoldAmounts holds, Clock clock) {
		Account account = Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "测试账户", "测试机构", AccountCurrency.CNY, null,
			AccountStatus.ACTIVE, null, USER_ID, CREATED_AT, CREATED_AT, 1);
		return new Fixture(account, membership,
			new FakeLedger(ledgerAmount == null ? null
				: new AccountBalanceReadPort.PostedPrimaryBalance(ledgerAmount, "CNY")),
			new FakeHolds(holds), clock);
	}

	private record Fixture(
		Account account,
		ActiveMembership membership,
		FakeLedger ledger,
		FakeHolds holds,
		Clock clock) {

		private AccountBalanceService service() {
			return new AccountBalanceService(
				new FakeAccounts(account), new FakeMembership(membership), ledger, holds,
				new DirectSnapshot(), clock);
		}
	}

	private static final class DirectSnapshot implements AccountBalanceSnapshotTransaction {
		@Override
		public <T> T read(Supplier<T> action) {
			return action.get();
		}
	}

	private static final class FakeAccounts implements AccountQueryReadPort {
		private final Account account;

		private FakeAccounts(Account account) {
			this.account = account;
		}

		@Override
		public Optional<Account> findById(UUID accountId) {
			return Optional.ofNullable(account);
		}

		@Override
		public List<Account> listByIds(Collection<UUID> accountIds, AccountKeysetPosition after, int maximumRecords) {
			return List.of();
		}
	}

	private static final class FakeMembership implements AccountMembershipReadPort {
		private final ActiveMembership membership;

		private FakeMembership(ActiveMembership membership) {
			this.membership = membership;
		}

		@Override
		public List<ActiveMembership> listActiveMemberships(UUID userId) {
			return membership == null ? List.of() : List.of(membership);
		}

		@Override
		public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
			return Optional.ofNullable(membership);
		}
	}

	private static final class FakeLedger implements AccountBalanceFactReadPort {
		private AccountBalanceReadPort.PostedPrimaryBalance balance;
		private Instant asOf;
		private int calls;

		private FakeLedger(AccountBalanceReadPort.PostedPrimaryBalance balance) {
			this.balance = balance;
		}

		@Override
		public Optional<AccountBalanceReadPort.PostedPrimaryBalance> findPostedPrimaryBalanceAt(
			UUID accountId, AccountBalanceFactReadPort.PrimaryNature primaryNature, Instant asOf) {
			calls++;
			this.asOf = asOf;
			return Optional.ofNullable(balance);
		}
	}

	private static final class FakeHolds implements LiquidityHoldBalanceReadPort {
		private final EffectiveHoldAmounts amounts;
		private Instant asOf;
		private int calls;

		private FakeHolds(EffectiveHoldAmounts amounts) {
			this.amounts = amounts;
		}

		@Override
		public EffectiveHoldAmounts sumEffectiveAt(UUID accountId, Instant asOf) {
			calls++;
			this.asOf = asOf;
			return amounts;
		}
	}

	private static final class CountingClock extends Clock {
		private final Instant instant;
		private int calls;

		private CountingClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			calls++;
			return instant;
		}
	}

}
