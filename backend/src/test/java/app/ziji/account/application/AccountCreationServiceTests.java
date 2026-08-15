package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountDomainException;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 账户创建服务的编排、校验和回滚测试；使用纯 Java fakes，不依赖 Spring 或数据库。 */
class AccountCreationServiceTests {

	private static final UUID CREATED_BY = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
	private static final Instant FIXED_NOW = Instant.parse("2026-08-15T03:04:05Z");

	@Test
	void createsAssetAccountWithPrimaryLedgerAndOwnerMembership() {
		FakeAccountStore accounts = new FakeAccountStore();
		FakeMemberInit memberInit = new FakeMemberInit();
		FakeLedgerInit ledgerInit = new FakeLedgerInit();
		AccountCreationService service = service(accounts, memberInit, ledgerInit);

		Account result = service.createAccount(command(AccountClass.ASSET, AccountType.BANK));

		assertNotNull(result.id());
		assertEquals(ACCOUNT_ID, result.id());
		assertEquals(AccountClass.ASSET, result.accountClass());
		assertEquals(AccountType.BANK, result.accountType());
		assertEquals(1, accounts.inserted.size());
		assertEquals(1, memberInit.calls.size());
		assertEquals(1, ledgerInit.primaryCalls.size());
		assertEquals(0, ledgerInit.positionCostCalls.size());
		// 所有写入使用同一个时间戳。
		assertEquals(FIXED_NOW, accounts.inserted.get(0).createdAt());
		assertEquals(FIXED_NOW, memberInit.calls.get(0).now());
		assertEquals(FIXED_NOW, memberInit.inclusionCalls.get(0).now());
		assertEquals(FIXED_NOW, ledgerInit.primaryCalls.get(0).now());
		// 账户、成员和科目使用同一个 accountId。
		UUID accountId = result.id();
		assertEquals(accountId, memberInit.calls.get(0).accountId());
		assertEquals(memberInit.membershipId, memberInit.inclusionCalls.get(0).membershipId());
		assertEquals(accountId, ledgerInit.primaryCalls.get(0).accountId());
	}

	@Test
	void createsInvestmentAccountWithPrimaryAndPositionCost() {
		FakeLedgerInit ledgerInit = new FakeLedgerInit();
		AccountCreationService service = service(new FakeAccountStore(), new FakeMemberInit(), ledgerInit);

		Account result = service.createAccount(command(AccountClass.INVESTMENT, AccountType.FUND));

		assertEquals(AccountClass.INVESTMENT, result.accountClass());
		assertEquals(1, ledgerInit.primaryCalls.size());
		assertEquals(1, ledgerInit.positionCostCalls.size());
		assertEquals(AccountClass.INVESTMENT, ledgerInit.primaryCalls.get(0).accountClass());
		assertEquals(AccountClass.INVESTMENT, ledgerInit.positionCostCalls.get(0).accountClass());
	}

	@Test
	void createsLiabilityAccountWithPrimaryLedger() {
		FakeLedgerInit ledgerInit = new FakeLedgerInit();
		AccountCreationService service = service(new FakeAccountStore(), new FakeMemberInit(), ledgerInit);

		Account result = service.createAccount(command(AccountClass.LIABILITY, AccountType.CONSUMER_LOAN));

		assertEquals(AccountClass.LIABILITY, result.accountClass());
		assertEquals(AccountType.CONSUMER_LOAN, result.accountType());
		assertEquals(1, ledgerInit.primaryCalls.size());
		assertEquals(0, ledgerInit.positionCostCalls.size());
		assertEquals(AccountClass.LIABILITY, ledgerInit.primaryCalls.get(0).accountClass());
	}

	@Test
	void rejectsNullCommand() {
		AccountCreationService service = serviceWithFakes();
		assertThrows(AccountCreationException.class, () -> service.createAccount(null));
	}

	@Test
	void rejectsIllegalClassTypeBeforeAnyWrite() {
		FakeAccountStore accounts = new FakeAccountStore();
		AccountCreationService service = service(accounts, new FakeMemberInit(), new FakeLedgerInit());

		// ASSET + FUND 是非法配对，Account.create 会拒绝。
		assertThrows(AccountDomainException.class, () ->
			service.createAccount(command(AccountClass.ASSET, AccountType.FUND)));
		assertEquals(0, accounts.inserted.size());
	}

	@Test
	void stopsBeforeLedgerInitializationWhenMemberCreationFails() {
		FakeAccountStore accounts = new FakeAccountStore();
		FailingMemberInit memberInit = new FailingMemberInit();
		FakeLedgerInit ledgerInit = new FakeLedgerInit();
		AccountCreationService service = service(accounts, memberInit, ledgerInit);

		assertThrows(RuntimeException.class, () ->
			service.createAccount(command(AccountClass.ASSET, AccountType.BANK)));
		assertEquals(1, accounts.inserted.size());
		assertEquals(0, ledgerInit.primaryCalls.size());
	}

	@Test
	void stopsAfterInclusionWhenPrimaryInitializationFails() {
		FakeAccountStore accounts = new FakeAccountStore();
		FakeMemberInit memberInit = new FakeMemberInit();
		FailingPrimaryInit ledgerInit = new FailingPrimaryInit();
		AccountCreationService service = service(accounts, memberInit, ledgerInit);

		assertThrows(RuntimeException.class, () ->
			service.createAccount(command(AccountClass.ASSET, AccountType.BANK)));
		assertEquals(1, accounts.inserted.size());
		assertEquals(1, memberInit.calls.size());
		assertEquals(1, memberInit.inclusionCalls.size());
	}

	@Test
	void rejectsNullDependency() {
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationService(null, new FakeAccountStore(), new FakeMemberInit(),
				new FakeLedgerInit(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC), () -> ACCOUNT_ID));
	}

	@Test
	void commandValidatesRequiredFields() {
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationCommand(null, AccountType.BANK, "名称", null,
				AccountCurrency.CNY, null, CREATED_BY));
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationCommand(AccountClass.ASSET, null, "名称", null,
				AccountCurrency.CNY, null, CREATED_BY));
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationCommand(AccountClass.ASSET, AccountType.BANK, "名称", null,
				null, null, CREATED_BY));
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationCommand(AccountClass.ASSET, AccountType.BANK, "名称", null,
				AccountCurrency.CNY, null, null));
	}

	// --- helpers ---

	private static AccountCreationService serviceWithFakes() {
		return service(new FakeAccountStore(), new FakeMemberInit(), new FakeLedgerInit());
	}

	private static AccountCreationService service(
		AccountStore accounts, AccountMemberInitPort memberInit, AccountLedgerInitializationPort ledgerInit) {
		return new AccountCreationService(
			new DirectTransactionRunner(), accounts, memberInit, ledgerInit,
			Clock.fixed(FIXED_NOW, ZoneOffset.UTC), () -> ACCOUNT_ID);
	}

	private static AccountCreationCommand command(AccountClass accountClass, AccountType accountType) {
		return new AccountCreationCommand(
			accountClass, accountType, "测试账户", "测试机构",
			AccountCurrency.CNY, "测试备注", CREATED_BY);
	}

	/** 直接执行的 TransactionRunner，不提供事务隔离语义，仅用于验证编排顺序。 */
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

	// --- fakes ---

	private static final class FakeAccountStore implements AccountStore {
		final List<Account> inserted = new ArrayList<>();
		@Override
		public void insert(Account account) { inserted.add(account); }
		@Override
		public java.util.Optional<Account> findById(UUID accountId) { return java.util.Optional.empty(); }
	}

	private static final class FakeMemberInit implements AccountMemberInitPort {
		final UUID membershipId = UUID.fromString("00000000-0000-0000-0000-000000000503");
		final List<MemberInitCall> calls = new ArrayList<>();
		final List<InclusionInitCall> inclusionCalls = new ArrayList<>();
		@Override
		public UUID initializeOwnerMembership(UUID accountId, UUID userId, Instant now) {
			calls.add(new MemberInitCall(accountId, userId, now));
			return membershipId;
		}
		@Override
		public void initializeInitialInclusion(UUID membershipId, UUID userId, Instant now) {
			inclusionCalls.add(new InclusionInitCall(membershipId, userId, now));
		}
	}

	private static final class FailingMemberInit implements AccountMemberInitPort {
		@Override
		public UUID initializeOwnerMembership(UUID accountId, UUID userId, Instant now) {
			throw new RuntimeException("成员初始化模拟失败。");
		}
		@Override
		public void initializeInitialInclusion(UUID membershipId, UUID userId, Instant now) {
			throw new AssertionError("成员初始化失败后不应写入计入设置。");
		}
	}

	private static final class FakeLedgerInit implements AccountLedgerInitializationPort {
		final List<LedgerInitCall> primaryCalls = new ArrayList<>();
		final List<LedgerInitCall> positionCostCalls = new ArrayList<>();
		@Override
		public void initializePrimary(UUID accountId, String accountClass, String currency, Instant now) {
			primaryCalls.add(new LedgerInitCall(accountId, AccountClass.valueOf(accountClass), now));
		}
		@Override
		public void initializePositionCost(UUID accountId, String currency, Instant now) {
			positionCostCalls.add(new LedgerInitCall(accountId, AccountClass.INVESTMENT, now));
		}
	}

	private static final class FailingPrimaryInit implements AccountLedgerInitializationPort {
		@Override
		public void initializePrimary(UUID accountId, String accountClass, String currency, Instant now) {
			throw new RuntimeException("PRIMARY 科目初始化模拟失败。");
		}
		@Override
		public void initializePositionCost(UUID accountId, String currency, Instant now) {
			throw new AssertionError("PRIMARY 初始化失败后不应写入 POSITION_COST。");
		}
	}

	private record MemberInitCall(UUID accountId, UUID userId, Instant now) {}
	private record InclusionInitCall(UUID membershipId, UUID userId, Instant now) {}
	private record LedgerInitCall(UUID accountId, AccountClass accountClass, Instant now) {}
}
