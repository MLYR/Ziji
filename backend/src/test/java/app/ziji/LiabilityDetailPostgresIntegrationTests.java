package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
import app.ziji.liability.application.LiabilityDetailPutCondition;
import app.ziji.liability.application.LiabilityDetailService;
import app.ziji.liability.application.LiabilityDetailStore;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class LiabilityDetailPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private LiabilityDetailService service;

	@Autowired
	private LiabilityDetailStore store;

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactions;

	@Test
	void realMigrationsPersistEmptyCreateReplaceAndPatchWithoutChangingAccountVersion() {
		UUID userId = insertUser("lifecycle");
		Account account = createAccount(userId, AccountType.OTHER, AccountCurrency.CNY);
		int accountVersion = accountVersion(account.id());

		assertEquals(0, service.get(userId, account.id()).version());
		LiabilityDetail created = service.put(userId, account.id(), LiabilityDetailPutCondition.initial(),
			values("0.05", "2026-01-01", "2027-01-01", 8, 20, "100.00"), "pg-liability-create-01")
			.detail();
		assertEquals(1, created.version());
		assertEquals(1, rowCount(account.id()));

		LiabilityDetail replaced = service.put(userId, account.id(), LiabilityDetailPutCondition.replace(1),
			values("0.06", "2026-01-01", "2027-02-01", 9, 21, "80.50"), "pg-liability-replace-01")
			.detail();
		assertEquals(2, replaced.version());

		LiabilityDetail patched = service.patch(userId, account.id(), 2,
			new LiabilityDetailPatch(false, null, false, null, false, null,
				true, null, true, 22, true, BigDecimal.ZERO), "pg-liability-patch-001").detail();
		assertEquals(3, patched.version());
		assertEquals(22, patched.repaymentDay());
		assertEquals(accountVersion, accountVersion(account.id()));
	}

	@Test
	void v002AndV007RejectInvalidAccountDateDayAndCurrencyPrecision() {
		UUID userId = insertUser("constraints");
		Account liability = createAccount(userId, AccountType.LOAN, AccountCurrency.JPY);
		Account asset = accountCreation.createAccount(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.BANK, "非负债账户", null, AccountCurrency.CNY, null, userId));

		assertThrows(DataAccessException.class, () -> insertRaw(asset.id(), null, null, null, null, null, null));
		assertThrows(DataAccessException.class,
			() -> insertRaw(liability.id(), null, LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 1), null, null, null));
		assertThrows(DataAccessException.class,
			() -> insertRaw(liability.id(), null, null, null, 0, null, null));
		assertThrows(DataAccessException.class,
			() -> insertRaw(liability.id(), null, null, null, null, null, new BigDecimal("10.01")));
		assertEquals(0, rowCount(liability.id()));
	}

	@Test
	void concurrentVersionConditionAllowsOnlyOneWinnerAndLeavesAccountVersionUntouched() throws Exception {
		UUID userId = insertUser("concurrent");
		Account account = createAccount(userId, AccountType.CREDIT_CARD, AccountCurrency.CNY);
		service.put(userId, account.id(), LiabilityDetailPutCondition.initial(),
			values("0.05", null, null, 8, 20, "100.00"), "pg-liability-concurrent-create");
		int accountVersion = accountVersion(account.id());
		LiabilityDetail base = store.findByAccountId(account.id()).orElseThrow();
		CountDownLatch start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Future<Boolean>> results = List.of(
				executor.submit(() -> updateAtVersionOne(base, new BigDecimal("80"), start)),
				executor.submit(() -> updateAtVersionOne(base, new BigDecimal("90"), start)));
			start.countDown();
			int winners = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) winners++;
			}
			assertEquals(1, winners);
		}

		LiabilityDetail persisted = store.findByAccountId(account.id()).orElseThrow();
		assertEquals(2, persisted.version());
		assertTrue(persisted.currentAmountDue().compareTo(new BigDecimal("80")) == 0
			|| persisted.currentAmountDue().compareTo(new BigDecimal("90")) == 0);
		assertEquals(accountVersion, accountVersion(account.id()));
	}

	private boolean updateAtVersionOne(LiabilityDetail base, BigDecimal amount, CountDownLatch start) throws Exception {
		start.await();
		return transactions.required(() -> {
			// 两个竞争者共享同一 version=1 快照，只让数据库 WHERE version=1 决定唯一胜者。
			LiabilityDetail replacement = base.replace(
				new LiabilityDetailValues(new BigDecimal("0.05"), null, null, 8, 20, amount), Instant.now());
			return store.updateIfVersion(replacement, 1).isPresent();
		});
	}

	private Account createAccount(UUID userId, AccountType type, AccountCurrency currency) {
		return accountCreation.createAccount(new AccountCreationCommand(
			AccountClass.LIABILITY, type, "负债-" + type + "-" + UUID.randomUUID(), null, currency, null, userId));
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "liability-pg-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '负债 PG', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return userId;
	}

	private void insertRaw(
		UUID accountId,
		BigDecimal interestRate,
		LocalDate loanDate,
		LocalDate dueDate,
		Integer billingDay,
		Integer repaymentDay,
		BigDecimal currentAmountDue) {
		jdbc.update("""
			INSERT INTO liability_details
				(account_id, interest_rate, loan_date, due_date, billing_day, repayment_day,
				 current_amount_due, updated_at, version)
			VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), 1)
			""", accountId, interestRate, loanDate, dueDate, billingDay, repaymentDay,
			currentAmountDue, Instant.now().toString());
	}

	private int rowCount(UUID accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM liability_details WHERE account_id = ?", Integer.class, accountId);
	}

	private int accountVersion(UUID accountId) {
		return jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, accountId);
	}

	private static LiabilityDetailValues values(
		String interestRate, String loanDate, String dueDate,
		Integer billingDay, Integer repaymentDay, String currentAmountDue) {
		return new LiabilityDetailValues(
			interestRate == null ? null : new BigDecimal(interestRate),
			loanDate == null ? null : LocalDate.parse(loanDate),
			dueDate == null ? null : LocalDate.parse(dueDate),
			billingDay, repaymentDay,
			currentAmountDue == null ? null : new BigDecimal(currentAmountDue));
	}
}
