package app.ziji;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationException;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountDomainException;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 验收：账户原子创建在真实数据库中写入全部附属事实，
 * V007 延迟约束在提交时通过，阶段性失败时完整回滚不留孤儿。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountCreationPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant FIXED_NOW = Instant.parse("2026-08-15T03:04:05Z");

	@Autowired
	private AccountStore accountStore;

	@Autowired
	private AccountMemberInitPort memberInit;

	@Autowired
	private AccountLedgerInitializationPort ledgerInit;

	@Autowired
	private AccountPostingAccessPort accountAccess;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Test
	void createsAssetAccountWithOwnerInclusionAndPrimaryLedger() {
		UUID userId = insertUser("create-asset");
		Account result = create(AccountClass.ASSET, AccountType.BANK, "工资卡", "示例银行",
			AccountCurrency.CNY, "日常开销", userId);

		assertNotNull(result.id());
		assertEquals(AccountClass.ASSET, result.accountClass());
		assertEquals(AccountType.BANK, result.accountType());
		assertEquals(result.id(), accountStore.findById(result.id()).orElseThrow().id());
		assertOwnerMembership(result.id(), userId);
		assertInclusionSetting(result.id(), userId);
		assertEquals(1, countLedgerAccounts(result.id()));
		assertPrimaryLedger(result.id(), "ASSET");
		assertNoPositionCost(result.id());
		assertAllCreationTimesUseFixedNow(result.id());
	}

	@Test
	void createsLiabilityAccountWithOwnerInclusionAndPrimaryLedger() {
		UUID userId = insertUser("create-liability");
		Account result = create(AccountClass.LIABILITY, AccountType.CREDIT_CARD, "信用卡", "发卡行",
			AccountCurrency.HKD, null, userId);

		assertEquals(1, countLedgerAccounts(result.id()));
		assertPrimaryLedger(result.id(), "LIABILITY");
		assertNoPositionCost(result.id());
		assertOwnerMembership(result.id(), userId);
		assertInclusionSetting(result.id(), userId);
		assertAllCreationTimesUseFixedNow(result.id());
	}

	@Test
	void createsInvestmentAccountWithPrimaryAndPositionCost() {
		UUID userId = insertUser("create-investment");
		Account result = create(AccountClass.INVESTMENT, AccountType.FUND, "场外基金", null,
			AccountCurrency.USD, null, userId);

		assertEquals(2, countLedgerAccounts(result.id()));
		assertPrimaryLedger(result.id(), "ASSET");
		Integer positionCostCount = jdbc.queryForObject("""
			SELECT count(*) FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'POSITION_COST' AND account_nature = 'ASSET'
			""", Integer.class, result.id());
		assertEquals(1, positionCostCount);
		assertOwnerMembership(result.id(), userId);
		assertInclusionSetting(result.id(), userId);
		assertAllCreationTimesUseFixedNow(result.id());
	}

	@Test
	void rejectsInvalidCommandBeforeAnyDatabaseWrite() {
		UUID userId = insertUser("create-invalid");
		UUID invalidNameId = UUID.randomUUID();
		AccountCreationService invalidNameService = service(
			accountStore, memberInit, ledgerInit, invalidNameId);

		assertThrows(AccountDomainException.class, () ->
			invalidNameService.createAccount(new AccountCreationCommand(
				AccountClass.ASSET, AccountType.BANK, " ", null,
				AccountCurrency.CNY, null, userId)));
		assertThrows(AccountDomainException.class, () ->
			service(accountStore, memberInit, ledgerInit, UUID.randomUUID()).createAccount(
				new AccountCreationCommand(
					AccountClass.ASSET, AccountType.FUND, "非法基金", null,
					AccountCurrency.CNY, null, userId)));
		assertThrows(AccountCreationException.class, () ->
			new AccountCreationCommand(AccountClass.ASSET, AccountType.BANK, "缺币种", null,
				null, null, userId));
		assertNoAccountFacts(invalidNameId);
		assertEquals(0, count("SELECT count(*) FROM accounts WHERE created_by = ?", userId));
	}

	@Test
	void incompleteAccountFailsAtCommitAndLeavesNoOrphanFacts() {
		UUID userId = insertUser("create-incomplete");
		UUID accountId = UUID.randomUUID();

		// 只写 accounts 不补齐附属事实，V007 延迟约束必须在真实提交时拒绝。
		assertThrows(RuntimeException.class, () ->
			transactionRunner.required(() -> jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, currency, status,
					 created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', '孤立账户', 'CNY', 'ACTIVE', ?, ?, ?, 1)
				""", accountId, userId, timestamp(), timestamp())));

		assertNoAccountFacts(accountId);
	}

	@Test
	void rollsBackAllFactsWhenAccountWriteFails() {
		UUID userId = insertUser("fail-account");
		UUID accountId = UUID.randomUUID();
		AccountStore failingStore = new AccountStore() {
			@Override
			public void insert(Account account) {
				accountStore.insert(account);
				throw new IllegalStateException("账户写入失败注入。");
			}

			@Override
			public Optional<Account> findById(UUID id) {
				return accountStore.findById(id);
			}
		};

		assertThrows(IllegalStateException.class, () ->
			service(failingStore, memberInit, ledgerInit, accountId).createAccount(command(
				AccountClass.ASSET, AccountType.BANK, userId)));
		assertNoAccountFacts(accountId);
	}

	@Test
	void rollsBackAllFactsWhenMembershipWriteFails() {
		UUID userId = insertUser("fail-membership");
		UUID accountId = UUID.randomUUID();
		AccountMemberInitPort failingMemberInit = new AccountMemberInitPort() {
			@Override
			public UUID initializeOwnerMembership(UUID id, UUID ownerId, Instant now) {
				memberInit.initializeOwnerMembership(id, ownerId, now);
				throw new IllegalStateException("成员写入失败注入。");
			}

			@Override
			public void initializeInitialInclusion(UUID membershipId, UUID ownerId, Instant now) {
				memberInit.initializeInitialInclusion(membershipId, ownerId, now);
			}
		};

		assertThrows(IllegalStateException.class, () ->
			service(accountStore, failingMemberInit, ledgerInit, accountId).createAccount(command(
				AccountClass.ASSET, AccountType.BANK, userId)));
		assertNoAccountFacts(accountId);
	}

	@Test
	void rollsBackAllFactsWhenInclusionWriteFails() {
		UUID userId = insertUser("fail-inclusion");
		UUID accountId = UUID.randomUUID();
		AccountMemberInitPort failingInclusionInit = new AccountMemberInitPort() {
			@Override
			public UUID initializeOwnerMembership(UUID id, UUID ownerId, Instant now) {
				return memberInit.initializeOwnerMembership(id, ownerId, now);
			}

			@Override
			public void initializeInitialInclusion(UUID membershipId, UUID ownerId, Instant now) {
				memberInit.initializeInitialInclusion(membershipId, ownerId, now);
				throw new IllegalStateException("计入设置写入失败注入。");
			}
		};

		assertThrows(IllegalStateException.class, () ->
			service(accountStore, failingInclusionInit, ledgerInit, accountId).createAccount(command(
				AccountClass.ASSET, AccountType.BANK, userId)));
		assertNoAccountFacts(accountId);
	}

	@Test
	void rollsBackAllFactsWhenPrimaryWriteFails() {
		UUID userId = insertUser("fail-primary");
		UUID accountId = UUID.randomUUID();
		AccountLedgerInitializationPort failingPrimaryInit = new AccountLedgerInitializationPort() {
			@Override
			public void initializePrimary(UUID id, String accountClass, String currency, Instant now) {
				ledgerInit.initializePrimary(id, accountClass, currency, now);
				throw new IllegalStateException("PRIMARY 写入失败注入。");
			}

			@Override
			public void initializePositionCost(UUID id, String currency, Instant now) {
				ledgerInit.initializePositionCost(id, currency, now);
			}
		};

		assertThrows(IllegalStateException.class, () ->
			service(accountStore, memberInit, failingPrimaryInit, accountId).createAccount(command(
				AccountClass.ASSET, AccountType.BANK, userId)));
		assertNoAccountFacts(accountId);
	}

	@Test
	void rollsBackAllFactsWhenPositionCostWriteFails() {
		UUID userId = insertUser("fail-position-cost");
		UUID accountId = UUID.randomUUID();
		AccountLedgerInitializationPort failingPositionCostInit = new AccountLedgerInitializationPort() {
			@Override
			public void initializePrimary(UUID id, String accountClass, String currency, Instant now) {
				ledgerInit.initializePrimary(id, accountClass, currency, now);
			}

			@Override
			public void initializePositionCost(UUID id, String currency, Instant now) {
				ledgerInit.initializePositionCost(id, currency, now);
				throw new IllegalStateException("POSITION_COST 写入失败注入。");
			}
		};

		assertThrows(IllegalStateException.class, () ->
			service(accountStore, memberInit, failingPositionCostInit, accountId).createAccount(command(
				AccountClass.INVESTMENT, AccountType.FUND, userId)));
		assertNoAccountFacts(accountId);
	}

	@Test
	void authorizationUsesActiveMembershipInsteadOfAccountCreator() {
		UUID ownerId = insertUser("membership-owner");
		UUID differentCreatorId = insertUser("membership-other");
		Account result = create(AccountClass.ASSET, AccountType.ALIPAY, "支付宝", null,
			AccountCurrency.CNY, null, ownerId);

		// 审计创建者可以变化，授权必须继续由 ACTIVE membership 事实决定。
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", differentCreatorId, result.id());

		assertTrue(accountAccess.mayPost(ownerId, result.id(), FIXED_NOW));
		assertTrue(!accountAccess.mayPost(differentCreatorId, result.id(), FIXED_NOW));
		assertOwnerMembership(result.id(), ownerId);
	}

	@Test
	void supportsAllFiveCurrencies() {
		for (AccountCurrency currency : AccountCurrency.values()) {
			UUID userId = insertUser("create-currency-" + currency.name().toLowerCase());
			Account result = create(AccountClass.ASSET, AccountType.CASH, "多币种账户", null,
				currency, null, userId);
			assertEquals(currency, result.currency());
			assertEquals(currency.name(), jdbc.queryForObject(
				"SELECT currency FROM ledger_accounts WHERE visible_account_id = ? AND ledger_role = 'PRIMARY'",
				String.class, result.id()));
		}
	}

	private Account create(
		AccountClass accountClass,
		AccountType accountType,
		String name,
		String institution,
		AccountCurrency currency,
		String note,
		UUID userId) {
		return service(accountStore, memberInit, ledgerInit, UUID.randomUUID()).createAccount(
			new AccountCreationCommand(accountClass, accountType, name, institution, currency, note, userId));
	}

	private AccountCreationService service(
		AccountStore store,
		AccountMemberInitPort members,
		AccountLedgerInitializationPort ledgers,
		UUID accountId) {
		return new AccountCreationService(
			transactionRunner,
			store,
			members,
			ledgers,
			Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
			() -> accountId);
	}

	private static AccountCreationCommand command(
		AccountClass accountClass,
		AccountType accountType,
		UUID userId) {
		return new AccountCreationCommand(
			accountClass, accountType, "失败注入账户", null, AccountCurrency.CNY, null, userId);
	}

	private void assertOwnerMembership(UUID accountId, UUID userId) {
		jdbc.query("""
			SELECT membership_no, role, status, version
			FROM account_members WHERE account_id = ? AND user_id = ?
			""", result -> {
			assertEquals(1, result.getInt("membership_no"));
			assertEquals("OWNER", result.getString("role"));
			assertEquals("ACTIVE", result.getString("status"));
			assertEquals(1, result.getInt("version"));
		}, accountId, userId);
	}

	private void assertInclusionSetting(UUID accountId, UUID userId) {
		jdbc.query("""
			SELECT s.included, s.ratio, s.valid_to
			FROM account_inclusion_settings s
			JOIN account_members m ON m.id = s.membership_id
			WHERE m.account_id = ? AND m.user_id = ?
			""", result -> {
			assertTrue(result.getBoolean("included"));
			assertEquals(0, result.getBigDecimal("ratio").compareTo(new BigDecimal("1.000000")));
			assertNull(result.getObject("valid_to"));
		}, accountId, userId);
	}

	private void assertAllCreationTimesUseFixedNow(UUID accountId) {
		jdbc.query("""
			SELECT a.created_at AS account_created_at, a.updated_at, m.joined_at,
				s.valid_from, s.created_at AS inclusion_created_at
			FROM accounts a
			JOIN account_members m ON m.account_id = a.id
			JOIN account_inclusion_settings s ON s.membership_id = m.id
			WHERE a.id = ?
			""", result -> {
			assertEquals(FIXED_NOW, instant(result, "account_created_at"));
			assertEquals(FIXED_NOW, instant(result, "updated_at"));
			assertEquals(FIXED_NOW, instant(result, "joined_at"));
			assertEquals(FIXED_NOW, instant(result, "valid_from"));
			assertEquals(FIXED_NOW, instant(result, "inclusion_created_at"));
		}, accountId);
		List<Instant> ledgerTimes = jdbc.query("""
			SELECT created_at FROM ledger_accounts
			WHERE visible_account_id = ?
			ORDER BY ledger_role
			""", (result, rowNum) -> result.getObject(1, OffsetDateTime.class).toInstant(), accountId);
		assertTrue(!ledgerTimes.isEmpty());
		assertTrue(ledgerTimes.stream().allMatch(FIXED_NOW::equals));
	}

	private static Instant instant(java.sql.ResultSet result, String column) throws java.sql.SQLException {
		return result.getObject(column, OffsetDateTime.class).toInstant();
	}

	private void assertPrimaryLedger(UUID accountId, String expectedNature) {
		Integer count = jdbc.queryForObject("""
			SELECT count(*) FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'PRIMARY' AND account_nature = ? AND status = 'ACTIVE'
			""", Integer.class, accountId, expectedNature);
		assertEquals(1, count);
	}

	private void assertNoPositionCost(UUID accountId) {
		Integer count = jdbc.queryForObject("""
			SELECT count(*) FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'POSITION_COST'
			""", Integer.class, accountId);
		assertEquals(0, count);
	}

	private void assertNoAccountFacts(UUID accountId) {
		assertEquals(0, count("SELECT count(*) FROM accounts WHERE id = ?", accountId));
		assertEquals(0, count("SELECT count(*) FROM account_members WHERE account_id = ?", accountId));
		assertEquals(0, count("""
			SELECT count(*)
			FROM account_inclusion_settings settings
			JOIN account_members members ON members.id = settings.membership_id
			WHERE members.account_id = ?
			""", accountId));
		assertEquals(0, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", accountId));
	}

	private int countLedgerAccounts(UUID accountId) {
		return count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", accountId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private java.sql.Timestamp timestamp() {
		return java.sql.Timestamp.from(FIXED_NOW);
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '创建测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, suffix + "@example.test", suffix + "@example.test",
			timestamp(), timestamp(), timestamp());
		return userId;
	}
}
