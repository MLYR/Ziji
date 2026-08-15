package app.ziji;

import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.AccountPersistenceException;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountDomainException;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL 验收：三类账户真实往返、字段无漂移，且适配器不写入成员或账务附属事实。 */
@SpringBootTest
@ActiveProfiles("test")
class AccountPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03Z");

	@Autowired
	private AccountStore store;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Test
	@Transactional
	void insertsAndReloadsAssetInvestmentAndLiabilityAccountsWithoutDrift() {
		// V007 延迟触发器只在提交时检查 OWNER/科目；测试事务回滚，用于证明适配器只写 accounts。
		UUID createdBy = insertUser("acc-roundtrip");
		Account asset = Account.create(
			UUID.randomUUID(), AccountClass.ASSET, AccountType.BANK, " 工资卡 ", " 示例银行 ",
			AccountCurrency.CNY, " 日常开销 ", createdBy, CREATED_AT);
		Account investment = Account.create(
			UUID.randomUUID(), AccountClass.INVESTMENT, AccountType.BROKERAGE, "美股券商", null,
			AccountCurrency.USD, null, createdBy, CREATED_AT);
		Account liability = Account.create(
			UUID.randomUUID(), AccountClass.LIABILITY, AccountType.CREDIT_CARD, "港币信用卡", "发卡行",
			AccountCurrency.HKD, "", createdBy, CREATED_AT);

		store.insert(asset);
		store.insert(investment);
		store.insert(liability);

		assertAccountEquals(asset, store.findById(asset.id()).orElseThrow());
		assertAccountEquals(investment, store.findById(investment.id()).orElseThrow());
		assertAccountEquals(liability, store.findById(liability.id()).orElseThrow());
		assertNoSideFacts(asset.id());
		assertNoSideFacts(investment.id());
		assertNoSideFacts(liability.id());
	}

	@Test
	@Transactional
	void restoresArchivedAccountAndRejectsIllegalDomainObjectsBeforeInsert() {
		UUID createdBy = insertUser("acc-archived");
		Instant archivedAt = Instant.parse("2026-08-15T05:00:00Z");
		Account archived = Account.restore(
			UUID.randomUUID(), AccountClass.LIABILITY, AccountType.LOAN, "历史借款", "亲友",
			AccountCurrency.EUR, "保留归档时间", AccountStatus.ARCHIVED, archivedAt,
			createdBy, CREATED_AT, archivedAt, 4);

		store.insert(archived);

		Account loaded = store.findById(archived.id()).orElseThrow();
		assertAccountEquals(archived, loaded);
		assertNoSideFacts(archived.id());
		assertThrows(AccountDomainException.class, () -> Account.create(
			UUID.randomUUID(), AccountClass.ASSET, AccountType.BROKERAGE, "非法配对", null,
			AccountCurrency.CNY, null, createdBy, CREATED_AT));
		assertThrows(AccountPersistenceException.class, () -> store.insert(null));
		assertTrue(store.findById(UUID.randomUUID()).isEmpty());
	}

	@Test
	void wrapsDatabaseFailuresWithoutLeakingSqlOrBusinessInput() {
		UUID missingUserId = UUID.fromString("00000000-0000-0000-0000-000000000499");
		String secretName = "不会出现在错误中的账户名";
		Account account = Account.create(
			UUID.randomUUID(), AccountClass.ASSET, AccountType.CASH, secretName, "秘密机构",
			AccountCurrency.JPY, "秘密备注", missingUserId, CREATED_AT);

		AccountPersistenceException exception = assertThrows(AccountPersistenceException.class,
			() -> store.insert(account));

		assertSafePersistenceFailure(exception, secretName, "秘密机构", missingUserId.toString());
		Integer count = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, account.id());
		assertEquals(0, count);
	}

	@Test
	void insertWithoutCompanionFactsFailsAtCommitAndLeavesNoAccountRow() {
		UUID createdBy = insertUser("acc-incomplete");
		Account account = Account.create(
			UUID.randomUUID(), AccountClass.ASSET, AccountType.BANK, "不会单独提交", null,
			AccountCurrency.CNY, null, createdBy, CREATED_AT);

		AccountPersistenceException exception = assertThrows(AccountPersistenceException.class,
			() -> store.insert(account));

		assertSafePersistenceFailure(exception, "不会单独提交", createdBy.toString(), account.id().toString());
		Integer count = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, account.id());
		assertEquals(0, count);
		assertNoSideFacts(account.id());
	}

	@Test
	void insertJoinsOuterTransactionAndCommitsAfterCompanionFactsAreAdded() {
		UUID createdBy = insertUser("acc-composed");
		UUID membershipId = UUID.randomUUID();
		Account account = Account.create(
			UUID.randomUUID(), AccountClass.ASSET, AccountType.BANK, "组合提交账户", null,
			AccountCurrency.CNY, null, createdBy, CREATED_AT);

		transactionRunner.required(() -> {
			store.insert(account);
			// 测试直接补齐 BE-ACC-002 将负责的关联事实，只验证 AccountStore 能加入同一外层事务。
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, account.id(), createdBy, java.sql.Timestamp.from(CREATED_AT));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, java.sql.Timestamp.from(CREATED_AT), createdBy,
				java.sql.Timestamp.from(CREATED_AT));
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", UUID.randomUUID(), account.id(), "ACCOUNT_" + account.id(),
				java.sql.Timestamp.from(CREATED_AT));
		});

		assertAccountEquals(account, store.findById(account.id()).orElseThrow());
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE account_id = ?", account.id()));
		assertEquals(1, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", account.id()));
	}

	private static void assertSafePersistenceFailure(
		AccountPersistenceException exception,
		String... secrets) {
		assertEquals("账户存储失败。", exception.getMessage());
		assertFalse(exception.getMessage().contains("INSERT"));
		assertFalse(exception.getMessage().contains("accounts"));
		for (String secret : secrets) {
			assertFalse(exception.getMessage().contains(secret), secret);
		}
	}

	private static void assertAccountEquals(Account expected, Account actual) {
		assertEquals(expected.id(), actual.id());
		assertEquals(expected.accountClass(), actual.accountClass());
		assertEquals(expected.accountType(), actual.accountType());
		assertEquals(expected.name(), actual.name());
		assertEquals(expected.institution(), actual.institution());
		assertEquals(expected.currency(), actual.currency());
		assertEquals(expected.note(), actual.note());
		assertEquals(expected.status(), actual.status());
		assertEquals(expected.archivedAt(), actual.archivedAt());
		assertEquals(expected.createdBy(), actual.createdBy());
		assertEquals(expected.createdAt(), actual.createdAt());
		assertEquals(expected.updatedAt(), actual.updatedAt());
		assertEquals(expected.version(), actual.version());
	}

	private void assertNoSideFacts(UUID accountId) {
		assertEquals(0, count("SELECT count(*) FROM account_members WHERE account_id = ?", accountId));
		assertEquals(0, count("""
			SELECT count(*)
			FROM account_inclusion_settings settings
			JOIN account_members members ON members.id = settings.membership_id
			WHERE members.account_id = ?
			""", accountId));
		assertEquals(0, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", accountId));
		assertEquals(0, count("SELECT count(*) FROM liability_details WHERE account_id = ?", accountId));
		assertEquals(0, count("SELECT count(*) FROM liquidity_holds WHERE account_id = ?", accountId));
		assertEquals(0, count("""
			SELECT count(*)
			FROM account_balance_snapshots snapshots
			JOIN ledger_accounts accounts ON accounts.id = snapshots.ledger_account_id
			WHERE accounts.visible_account_id = ?
			""", accountId));
		assertEquals(0, count("SELECT count(*) FROM account_liquidity_snapshots WHERE account_id = ?", accountId));
	}

	private int count(String sql, UUID accountId) {
		Integer value = jdbc.queryForObject(sql, Integer.class, accountId);
		return value == null ? 0 : value;
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '账户测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, suffix + "@example.test", suffix + "@example.test",
			java.sql.Timestamp.from(CREATED_AT), java.sql.Timestamp.from(CREATED_AT),
			java.sql.Timestamp.from(CREATED_AT));
		return userId;
	}
}
