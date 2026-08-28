package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.ziji.account.application.AccountBalanceResult;
import app.ziji.account.application.AccountBalanceSnapshotTransaction;
import app.ziji.account.application.AccountBalanceUseCase;
import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.ledger.application.BalanceProjectionRebuildResult;
import app.ziji.ledger.application.BalanceProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 余额真实 PostgreSQL 事实读取、时区边界、权限和 REPEATABLE READ 快照测试。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountBalancePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant ACCOUNT_CREATED_AT = Instant.parse("2026-08-15T00:00:00Z");
	private static final Instant AS_OF = Instant.parse("2026-08-16T00:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AccountBalanceUseCase balanceUseCase;

	@Autowired
	private AccountBalanceSnapshotTransaction snapshotTransaction;

	@Autowired
	private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

	@Autowired
	private BalanceProjectionService projections;

	@Test
	void readsBackdatedPostedLedgerAndOnlyEffectiveHoldsAtOneAsOf() {
		UserFixture owner = insertUser("balance-facts-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15),
			"Asia/Shanghai", Instant.parse("2026-08-15T12:00:00Z"), new BigDecimal("100.00"), true,
			null, null, null, 1);
		// 已入账事实的业务日已在 asOf 当地日期内；posted_at 晚于 asOf 不能排除该 LedgerEntry。
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15),
			"Asia/Shanghai", Instant.parse("2026-08-16T01:00:00Z"), new BigDecimal("70.00"), true,
			null, null, null, 1);

		seedHold(account, "FROZEN", new BigDecimal("30.00"),
			Instant.parse("2026-08-15T01:00:00Z"), null, null, null, null);
		seedHold(account, "IN_TRANSIT", new BigDecimal("20.00"),
			Instant.parse("2026-08-15T02:00:00Z"), Instant.parse("2026-08-16T01:00:00Z"), null, null, null);
		// 未来生效、已释放和到达 expires_at 的占用均不应进入同一时点的合计。
		seedHold(account, "RESERVED", new BigDecimal("40.00"),
			Instant.parse("2026-08-16T01:00:00Z"), null, null, null, null);
		seedHold(account, "FROZEN", new BigDecimal("50.00"),
			Instant.parse("2026-08-15T03:00:00Z"), null, Instant.parse("2026-08-15T12:00:00Z"),
			"RELEASED", Instant.parse("2026-08-15T12:00:00Z"));
		seedHold(account, "RESERVED", new BigDecimal("60.00"),
			Instant.parse("2026-08-15T03:00:00Z"), AS_OF, null, null, null);

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("170.00"), result.ledgerBalance());
		assertEquals(new BigDecimal("50.00"), result.unavailableAmount());
		assertEquals(new BigDecimal("30.00"), result.unavailableBreakdown().frozen());
		assertEquals(new BigDecimal("20.00"), result.unavailableBreakdown().inTransit());
		assertEquals(new BigDecimal("0.00"), result.unavailableBreakdown().reserved());
		assertEquals(new BigDecimal("120.00"), result.availableBalance());
		assertEquals(AccountBalanceResult.LiquidityStatus.NORMAL, result.liquidityStatus());
	}

	@Test
	void postgresReturnsNegativeAvailableBalanceWithoutClamping() {
		UserFixture owner = insertUser("balance-negative-available-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15), "UTC",
			Instant.parse("2026-08-15T01:00:00Z"), new BigDecimal("10.00"), true,
			null, null, null, 1);
		seedHold(account, "FROZEN", new BigDecimal("6.00"),
			Instant.parse("2026-08-15T02:00:00Z"), null, null, null, null);
		seedHold(account, "IN_TRANSIT", new BigDecimal("3.00"),
			Instant.parse("2026-08-15T03:00:00Z"), null, null, null, null);
		seedHold(account, "RESERVED", new BigDecimal("4.00"),
			Instant.parse("2026-08-15T04:00:00Z"), null, null, null, null);

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(account.accountId(), result.accountId());
		assertEquals(AccountCurrency.CNY, result.currency());
		assertMoney(new BigDecimal("10.00"), result.ledgerBalance());
		assertMoney(new BigDecimal("6.00"), result.unavailableBreakdown().frozen());
		assertMoney(new BigDecimal("3.00"), result.unavailableBreakdown().inTransit());
		assertMoney(new BigDecimal("4.00"), result.unavailableBreakdown().reserved());
		assertMoney(new BigDecimal("13.00"), result.unavailableAmount());
		assertEquals(0, result.unavailableBreakdown().total().compareTo(result.unavailableAmount()));
		assertMoney(new BigDecimal("-3.00"), result.availableBalance());
		assertEquals(AccountBalanceResult.LiquidityStatus.NEGATIVE_AVAILABLE, result.liquidityStatus());
		assertEquals(AS_OF, result.asOf());
		assertEquals(0, result.asOfSequence());
	}

	@Test
	void directBalanceIgnoresStaleOrMissingProjectionsAndMatchesLedgerRebuild() {
		UserFixture owner = insertUser("balance-projection-consistency-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15), "UTC",
			Instant.parse("2026-08-15T01:00:00Z"), new BigDecimal("100.00"), true,
			null, null, null, 1);
		seedHold(account, "FROZEN", new BigDecimal("6.00"),
			Instant.parse("2026-08-15T02:00:00Z"), null, null, null, null);
		seedHold(account, "IN_TRANSIT", new BigDecimal("3.00"),
			Instant.parse("2026-08-15T03:00:00Z"), null, null, null, null);
		seedHold(account, "RESERVED", new BigDecimal("4.00"),
			Instant.parse("2026-08-15T04:00:00Z"), null, null, null, null);
		long changeLogSequence = jdbc.queryForObject("""
			INSERT INTO change_log (
				entity_type, entity_id, entity_version, change_type,
				recipient_user_id, account_id, changed_at, payload_version, payload)
			VALUES ('TRANSACTION', ?, 1, 'UPSERT', ?, ?, CAST(? AS timestamptz), 1, CAST(? AS jsonb))
			RETURNING sequence
			""", Long.class, UUID.randomUUID(), owner.userId(), account.accountId(), ts(AS_OF), "{}");
		assertTrue(changeLogSequence > 0);
		assertTrue(jdbc.queryForObject("SELECT MAX(sequence) FROM change_log", Long.class) > 0);

		AccountBalanceResult directBefore = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);
		assertEquals(account.accountId(), directBefore.accountId());
		assertEquals(AccountCurrency.CNY, directBefore.currency());
		assertMoney(new BigDecimal("100.00"), directBefore.ledgerBalance());
		assertMoney(new BigDecimal("6.00"), directBefore.unavailableBreakdown().frozen());
		assertMoney(new BigDecimal("3.00"), directBefore.unavailableBreakdown().inTransit());
		assertMoney(new BigDecimal("4.00"), directBefore.unavailableBreakdown().reserved());
		assertMoney(new BigDecimal("13.00"), directBefore.unavailableAmount());
		assertMoney(new BigDecimal("87.00"), directBefore.availableBalance());
		assertEquals(AccountBalanceResult.LiquidityStatus.NORMAL, directBefore.liquidityStatus());
		assertEquals(AS_OF, directBefore.asOf());
		assertEquals(0, directBefore.asOfSequence());

		Map<String, Long> factCountsBefore = factCounts();
		Map<String, List<Map<String, Object>>> factsBefore = factSnapshots(account);
		// 投影是可删除的缓存；先清空旧缓存，再写入满足数据库约束但数值错误的值，验证精确余额仍以事实为准。
		jdbc.update("DELETE FROM account_balance_snapshots");
		jdbc.update("DELETE FROM account_liquidity_snapshots");
		jdbc.update("""
			INSERT INTO account_balance_snapshots
				(ledger_account_id, business_date, balance, currency, as_of_change_sequence, calculated_at)
			VALUES (?, ?, ?, 'CNY', 123, ?)
			""", account.primaryLedgerId(), LocalDate.of(2026, 8, 15), new BigDecimal("999.00"), ts(AS_OF));
		jdbc.update("""
			INSERT INTO account_liquidity_snapshots
				(account_id, business_date, ledger_balance, unavailable_amount, available_balance,
				 currency, as_of_change_sequence, calculated_at)
			VALUES (?, ?, ?, ?, ?, 'CNY', 456, ?)
			""", account.accountId(), LocalDate.of(2026, 8, 15), new BigDecimal("999.00"),
			new BigDecimal("1.00"), new BigDecimal("998.00"), ts(AS_OF));
		assertBalanceEquals(directBefore,
			balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF));

		BalanceProjectionRebuildResult staleRebuild = projections.rebuildAll();
		assertTrue(staleRebuild.previousDifferenceCount() > 0);
		assertEquals(0, staleRebuild.differenceCount());
		assertBalanceEquals(directBefore,
			balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF));
		assertEquals(factCountsBefore, factCounts());
		assertEquals(factsBefore, factSnapshots(account));

		// 删除投影不能删除 Transaction、LedgerEntry、LiquidityHold，也不能让直接读取退化为零或丢失占用。
		jdbc.update("DELETE FROM account_balance_snapshots");
		jdbc.update("DELETE FROM account_liquidity_snapshots");
		assertBalanceEquals(directBefore,
			balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF));
		assertEquals(factCountsBefore, factCounts());
		assertEquals(factsBefore, factSnapshots(account));

		BalanceProjectionRebuildResult rebuilt = projections.rebuildAll();

		assertEquals(rebuilt.snapshotCount(), rebuilt.previousDifferenceCount());
		assertTrue(rebuilt.snapshotCount() > 0);
		assertTrue(rebuilt.previousDifferenceCount() > 0);
		assertEquals(0, rebuilt.differenceCount());
		Map<String, Object> primarySnapshot = jdbc.queryForMap("""
			SELECT balance, currency, as_of_change_sequence
			FROM account_balance_snapshots
			WHERE ledger_account_id = ? AND business_date = ?
			""", account.primaryLedgerId(), LocalDate.of(2026, 8, 15));
		assertMoney(directBefore.ledgerBalance(), (BigDecimal) primarySnapshot.get("balance"));
		assertEquals("CNY", String.valueOf(primarySnapshot.get("currency")).trim());
		// 当前没有全局余额事实 sequence；projection 与余额 API 都只能使用 0，不能借用 change_log.sequence。
		assertEquals(0L, ((Number) primarySnapshot.get("as_of_change_sequence")).longValue());
		assertEquals(0, directBefore.asOfSequence());

		assertBalanceEquals(directBefore,
			balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF));
		assertEquals(factCountsBefore, factCounts());
		assertEquals(factsBefore, factSnapshots(account));
	}

	@Test
	void countsBackdatedRevisionFromEffectiveAtWithoutDoubleCountingPreviousVersion() {
		UserFixture owner = insertUser("balance-hold-history-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15),
			"UTC", Instant.parse("2026-08-15T01:00:00Z"), new BigDecimal("100.00"), true,
			null, null, null, 1);
		UUID rootHoldId = UUID.randomUUID();
		Instant revisionCreatedAt = AS_OF.plusSeconds(1);
		Instant revisionEffectiveAt = Instant.parse("2026-08-15T01:00:00Z");
		seedHoldVersion(account, "FROZEN", new BigDecimal("10.00"),
			revisionEffectiveAt, null, null, "SUPERSEDED", revisionEffectiveAt,
			rootHoldId, rootHoldId, null, 1, Instant.parse("2026-08-15T00:00:00Z"));
		// 修订事实的有效边界由 effective_at 决定；created_at 晚于 asOf 不应覆盖这一业务时态。
		seedHoldVersion(account, "FROZEN", new BigDecimal("20.00"),
			revisionEffectiveAt, null, null, null, null,
			UUID.randomUUID(), rootHoldId, rootHoldId, 2, revisionCreatedAt);

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("20.00"), result.unavailableAmount());
		assertEquals(new BigDecimal("20.00"), result.unavailableBreakdown().frozen());
		assertEquals(new BigDecimal("80.00"), result.availableBalance());
	}

	@Test
	void appliesEachTransactionTimezoneAndUsesBusinessDateForAsOfCutoff() {
		UserFixture owner = insertUser("balance-timezone-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15),
			"Asia/Shanghai", Instant.parse("2026-08-15T10:00:00Z"), new BigDecimal("100.00"), true,
			null, null, null, 1);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 16),
			"Asia/Shanghai", Instant.parse("2026-08-15T15:00:00Z"), new BigDecimal("25.00"), true,
			null, null, null, 1);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 16),
			"UTC", Instant.parse("2026-08-15T15:00:00Z"), new BigDecimal("40.00"), true,
			null, null, null, 1);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15),
			"Asia/Shanghai", Instant.parse("2026-08-15T17:00:00Z"), new BigDecimal("70.00"), true,
			null, null, null, 1);
		// 负 offset 交易在 AS_OF 时仍处于 8 月 15 日当地日期，8 月 16 日业务事实必须排除。
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 16),
			"America/Los_Angeles", Instant.parse("2026-08-15T23:00:00Z"), new BigDecimal("55.00"), true,
			null, null, null, 1);

		AccountBalanceResult beforeShanghaiMidnight = balanceUseCase.getBalance(
			owner.userId(), account.accountId(), Instant.parse("2026-08-15T15:59:59Z"));
		AccountBalanceResult afterShanghaiMidnight = balanceUseCase.getBalance(
			owner.userId(), account.accountId(), Instant.parse("2026-08-15T16:00:00Z"));
		AccountBalanceResult atAsOf = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("170.00"), beforeShanghaiMidnight.ledgerBalance());
		assertEquals(new BigDecimal("195.00"), afterShanghaiMidnight.ledgerBalance());
		assertEquals(new BigDecimal("235.00"), atAsOf.ledgerBalance());
	}

	@Test
	void excludesPostedBeforeAsOfWhenBusinessDateIsAfterTransactionLocalAsOfDate() {
		UserFixture owner = insertUser("balance-future-business-date-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		// posted_at 已早于 asOf，但固化业务日仍在 UTC 当地日期之后，不能进入余额。
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 17),
			"UTC", Instant.parse("2026-08-15T23:59:59Z"), new BigDecimal("25.00"), true,
			null, null, null, 1);

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("0.00"), result.ledgerBalance());
	}

	@Test
	void excludesUnpostedLedgerFactsEvenWhenBusinessDateIsBeforeAsOf() {
		UserFixture owner = insertUser("balance-unposted-fact-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		// DRAFT 交易即使预先写入 LedgerEntry，也未形成可计入余额的已入账事实。
		seedUnpostedTransaction(account, LocalDate.of(2026, 8, 15), "UTC", new BigDecimal("35.00"));

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("0.00"), result.ledgerBalance());
	}

	@Test
	void keepsOriginalReversalAndReplacementPostedFactsInTheBalance() {
		UserFixture owner = insertUser("balance-reversal-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		TransactionFixture original = seedPostedTransaction(account, "ADJUSTMENT", "SUPERSEDED",
			LocalDate.of(2026, 8, 15), "UTC", Instant.parse("2026-08-15T01:00:00Z"),
			new BigDecimal("100.00"), true, null, null, null, 1);
		seedPostedTransaction(account, "REVERSAL", "POSTED", LocalDate.of(2026, 8, 15), "UTC",
			Instant.parse("2026-08-15T02:00:00Z"), new BigDecimal("100.00"), false,
			null, null, original.transactionId(), 1);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15), "UTC",
			Instant.parse("2026-08-15T03:00:00Z"), new BigDecimal("150.00"), true,
			original.transactionId(), original.transactionId(), null, 2);

		AccountBalanceResult result = balanceUseCase.getBalance(owner.userId(), account.accountId(), AS_OF);

		assertEquals(new BigDecimal("150.00"), result.ledgerBalance());
	}

	@Test
	void ownerEditorViewerCanReadButInactiveAndUnrelatedMembershipsCannot() throws Exception {
		UserFixture owner = insertUser("balance-permission-owner");
		UserFixture editor = insertUser("balance-permission-editor");
		UserFixture viewer = insertUser("balance-permission-viewer");
		UserFixture left = insertUser("balance-permission-left");
		UserFixture removed = insertUser("balance-permission-removed");
		UserFixture stranger = insertUser("balance-permission-stranger");
		AccountFixture account = seedAccount(owner, "CNY", true);
		addMembership(account, editor, "EDITOR", "ACTIVE", ACCOUNT_CREATED_AT, null);
		addMembership(account, viewer, "VIEWER", "ACTIVE", ACCOUNT_CREATED_AT, null);
		addMembership(account, left, "VIEWER", "LEFT", ACCOUNT_CREATED_AT, Instant.parse("2026-08-15T12:00:00Z"));
		addMembership(account, removed, "EDITOR", "REMOVED", ACCOUNT_CREATED_AT, Instant.parse("2026-08-15T12:00:00Z"));

		for (UserFixture readable : List.of(owner, editor, viewer)) {
			mvc.perform(get(path(account)).with(user(readable.userId().toString())).param("asOf", AS_OF.toString()))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.ETAG));
		}
		for (UserFixture invisible : List.of(left, removed, stranger)) {
			mvc.perform(get(path(account)).with(user(invisible.userId().toString())).param("asOf", AS_OF.toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		}

		assertThrows(AccountNotVisibleException.class,
			() -> balanceUseCase.getBalance(stranger.userId(), account.accountId(), AS_OF));
	}

	@Test
	void authenticationAndRequestValidationPrecedeBalanceFactReads() throws Exception {
		UserFixture owner = insertUser("balance-precedence-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);

		mvc.perform(get(path(account)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get(path(account)).with(user(owner.userId().toString()))
				.param("asOf", "2026-08-16T00:00:00"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void balanceReadDoesNotCreateFactsProjectionsAuditOutboxOrIdempotencyRows() throws Exception {
		UserFixture owner = insertUser("balance-readonly-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		seedPostedTransaction(account, "ADJUSTMENT", "POSTED", LocalDate.of(2026, 8, 15), "UTC",
			Instant.parse("2026-08-15T01:00:00Z"), new BigDecimal("10.00"), true,
			null, null, null, 1);
		Map<String, Long> before = counts();

		mvc.perform(get(path(account)).with(user(owner.userId().toString())).param("asOf", AS_OF.toString()))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG));

		assertEquals(before, counts());
	}

	@Test
	void repeatableReadSnapshotDoesNotMixRowsBeforeAndAfterAConcurrentCommit() throws Exception {
		UserFixture owner = insertUser("balance-snapshot-owner");
		AccountFixture account = seedAccount(owner, "CNY", true);
		CountDownLatch firstReadComplete = new CountDownLatch(1);
		CountDownLatch writerCommitted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> consistentRead = executor.submit(() -> snapshotTransaction.read(() -> {
				int before = countHolds(account.accountId());
				firstReadComplete.countDown();
				await(writerCommitted);
				int after = countHolds(account.accountId());
				return after - before;
			}));
			await(firstReadComplete);
			Future<?> writer = executor.submit(() -> {
				seedHold(account, "FROZEN", new BigDecimal("1.00"),
					Instant.parse("2026-08-15T01:00:00Z"), null, null, null, null);
				writerCommitted.countDown();
				return null;
			});
			writer.get(10, TimeUnit.SECONDS);
			assertEquals(0, consistentRead.get(10, TimeUnit.SECONDS));
			assertEquals(1, countHolds(account.accountId()));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "余额快照并发测试线程未在超时内终止");
		}
	}

	private Map<String, Long> counts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of(
			"transactions", "ledger_entries", "liquidity_holds", "audit_logs", "outbox_events",
			"idempotency_records", "account_balance_snapshots", "account_liquidity_snapshots")) {
			counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private Map<String, Long> factCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of(
			"accounts", "ledger_accounts", "transactions", "ledger_entries", "liquidity_holds",
			"audit_logs", "outbox_events", "idempotency_records", "change_log")) {
			counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private Map<String, List<Map<String, Object>>> factSnapshots(AccountFixture account) {
		Map<String, List<Map<String, Object>>> facts = new LinkedHashMap<>();
		// 对账验收不能只比较总行数；保存本 fixture 的关键列，才能证明重建没有改写账务事实。
		facts.put("accounts", jdbc.queryForList("""
			SELECT id, account_class, account_type, name, institution, currency, note, status,
				archived_at, created_by, created_at, updated_at, version
			FROM accounts
			WHERE id = ?
			""", account.accountId()));
		facts.put("ledger_accounts", jdbc.queryForList("""
			SELECT id, visible_account_id, owner_user_id, code, ledger_role, account_nature,
				currency, status, created_at
			FROM ledger_accounts
			WHERE id IN (?, ?)
			ORDER BY id
			""", account.primaryLedgerId(), account.systemLedgerId()));
		facts.put("transactions", jdbc.queryForList("""
			SELECT t.id, t.transaction_type, t.status, t.business_date, t.timezone,
				t.root_transaction_id, t.previous_version_id, t.reversal_of_id, t.version_no,
				t.posted_at, t.created_by, t.updated_by, t.created_at, t.updated_at, t.entity_version
			FROM transactions t
			WHERE t.id IN (
				SELECT e.transaction_id
				FROM ledger_entries e
				WHERE e.ledger_account_id = ?
			)
			ORDER BY t.id
			""", account.primaryLedgerId()));
		facts.put("ledger_entries", jdbc.queryForList("""
			SELECT e.id, e.transaction_id, e.ledger_account_id, e.sequence_no, e.direction,
				e.amount, e.currency, e.business_date, e.created_at
			FROM ledger_entries e
			WHERE e.transaction_id IN (
				SELECT related.transaction_id
				FROM ledger_entries related
				WHERE related.ledger_account_id = ?
			)
			ORDER BY e.transaction_id, e.sequence_no
			""", account.primaryLedgerId()));
		facts.put("liquidity_holds", jdbc.queryForList("""
			SELECT id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at,
				source, note, root_hold_id, previous_revision_id, revision_no, ended_at, end_reason,
				created_by, created_at, updated_at, version
			FROM liquidity_holds
			WHERE account_id = ?
			ORDER BY root_hold_id, revision_no
			""", account.accountId()));
		return facts;
	}

	private void assertBalanceEquals(AccountBalanceResult expected, AccountBalanceResult actual) {
		assertEquals(expected.accountId(), actual.accountId());
		assertEquals(expected.currency(), actual.currency());
		assertMoney(expected.ledgerBalance(), actual.ledgerBalance());
		assertMoney(expected.unavailableAmount(), actual.unavailableAmount());
		assertMoney(expected.unavailableBreakdown().frozen(), actual.unavailableBreakdown().frozen());
		assertMoney(expected.unavailableBreakdown().inTransit(), actual.unavailableBreakdown().inTransit());
		assertMoney(expected.unavailableBreakdown().reserved(), actual.unavailableBreakdown().reserved());
		assertMoney(expected.availableBalance(), actual.availableBalance());
		assertEquals(expected.liquidityStatus(), actual.liquidityStatus());
		assertEquals(expected.asOf(), actual.asOf());
		assertEquals(expected.asOfSequence(), actual.asOfSequence());
	}

	private void assertMoney(BigDecimal expected, BigDecimal actual) {
		assertEquals(0, expected.compareTo(actual));
	}

	private int countHolds(UUID accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, accountId);
	}

	private String path(AccountFixture account) {
		return "/api/v1/accounts/" + account.accountId() + "/balance";
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "balance-" + suffix + "-" + UUID.randomUUID() + "@example.test";
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '余额测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, ACCOUNT_CREATED_AT.toString(), ACCOUNT_CREATED_AT.toString(), ACCOUNT_CREATED_AT.toString());
		return new UserFixture(userId, email);
	}

	private AccountFixture seedAccount(UserFixture owner, String currency, boolean withPrimary) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		UUID primaryLedgerId = UUID.randomUUID();
		UUID systemLedgerId = UUID.randomUUID();
		transactionTemplate.executeWithoutResult(status -> {
			jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, institution, currency, note, status,
					 archived_at, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, '余额测试机构', ?, NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, "余额账户-" + accountId.toString().substring(0, 8), currency,
				owner.userId(), ts(ACCOUNT_CREATED_AT), ts(ACCOUNT_CREATED_AT));
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, owner.userId(), ts(ACCOUNT_CREATED_AT));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(ACCOUNT_CREATED_AT), owner.userId(), ts(ACCOUNT_CREATED_AT));
			if (withPrimary) {
				jdbc.update("""
					INSERT INTO ledger_accounts
						(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
					VALUES (?, ?, ?, 'PRIMARY', 'ASSET', ?, 'ACTIVE', ?)
					""", primaryLedgerId, accountId, "ACCOUNT_" + accountId, currency, ts(ACCOUNT_CREATED_AT));
			}
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, NULL, ?, ?, 'SYSTEM', 'EQUITY', ?, 'ACTIVE', ?)
				""", systemLedgerId, owner.userId(), "BALANCE_TEST_" + accountId, currency, ts(ACCOUNT_CREATED_AT));
		});
		return new AccountFixture(accountId, owner.userId(),
			withPrimary ? primaryLedgerId : null, systemLedgerId, currency);
	}

	private void addMembership(
		AccountFixture account,
		UserFixture user,
		String role,
		String status,
		Instant joinedAt,
		Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO account_members
				(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
			VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
			""", membershipId, account.accountId(), user.userId(), role, status, ts(joinedAt),
			endedAt == null ? null : ts(endedAt));
		if ("ACTIVE".equals(status)) {
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 0.500000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(joinedAt), user.userId(), ts(joinedAt));
		}
	}

	private TransactionFixture seedPostedTransaction(
		AccountFixture account,
		String type,
		String finalStatus,
		LocalDate businessDate,
		String timezone,
		Instant postedAt,
		BigDecimal amount,
		boolean debitPrimary,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo) {
		UUID transactionId = UUID.randomUUID();
		UUID rootId = rootTransactionId == null ? transactionId : rootTransactionId;
		Instant businessAt = postedAt == null ? ACCOUNT_CREATED_AT : postedAt;
		Instant createdAt = postedAt == null ? ACCOUNT_CREATED_AT : postedAt.minusSeconds(1);
		transactionTemplate.executeWithoutResult(status -> {
			jdbc.update("""
				INSERT INTO transactions
					(id, transaction_type, status, business_at, business_date, timezone, source,
					 root_transaction_id, previous_version_id, reversal_of_id, version_no,
					 created_by, updated_by, created_at, updated_at, entity_version)
				VALUES (?, ?, 'DRAFT', CAST(? AS timestamptz), ?, ?, 'ADJUSTMENT', ?, ?, ?, ?, ?, ?, ?, ?, 1)
				""", transactionId, type, ts(businessAt), java.sql.Date.valueOf(businessDate), timezone,
				rootId, previousVersionId, reversalOfId, versionNo, account.ownerId(), account.ownerId(),
				ts(createdAt), ts(createdAt));
			jdbc.update("""
				INSERT INTO ledger_entries
					(id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
				VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?), (?, ?, ?, 2, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), transactionId, account.primaryLedgerId(), debitPrimary ? "D" : "C", amount,
				account.currency(), java.sql.Date.valueOf(businessDate), ts(createdAt), UUID.randomUUID(), transactionId,
				account.systemLedgerId(), debitPrimary ? "C" : "D", amount, account.currency(),
				java.sql.Date.valueOf(businessDate), ts(createdAt));
			if (postedAt != null) {
				jdbc.update("""
					UPDATE transactions
					SET status = ?, posted_at = CAST(? AS timestamptz), updated_at = CAST(? AS timestamptz)
					WHERE id = ?
					""", finalStatus, ts(postedAt), ts(postedAt), transactionId);
			}
		});
		return new TransactionFixture(transactionId);
	}

	private void seedUnpostedTransaction(
		AccountFixture account,
		LocalDate businessDate,
		String timezone,
		BigDecimal amount) {
		// 复用与已入账事实相同的平衡分录夹具，只省略最终 posting，验证 posted_at 非空门槛仍保留。
		seedPostedTransaction(account, "ADJUSTMENT", "DRAFT", businessDate, timezone, null, amount, true,
			null, null, null, 1);
	}

	private void seedHold(
		AccountFixture account,
		String holdType,
		BigDecimal amount,
		Instant effectiveAt,
		Instant expiresAt,
		Instant releasedAt,
		String endReason,
		Instant endedAt) {
		UUID holdId = UUID.randomUUID();
		seedHoldVersion(account, holdType, amount, effectiveAt, expiresAt, releasedAt, endReason, endedAt,
			holdId, holdId, null, 1, effectiveAt.minusSeconds(1));
	}

	private void seedHoldVersion(
		AccountFixture account,
		String holdType,
		BigDecimal amount,
		Instant effectiveAt,
		Instant expiresAt,
		Instant releasedAt,
		String endReason,
		Instant endedAt,
		UUID holdId,
		UUID rootHoldId,
		UUID previousRevisionId,
		int revisionNo,
		Instant createdAt) {
		jdbc.update("""
			INSERT INTO liquidity_holds
				(id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				 root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
				 updated_at, version)
			VALUES (?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), 'MANUAL', '余额测试占用',
				 ?, ?, ?, CAST(? AS timestamptz), ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", holdId, account.accountId(), holdType, amount, account.currency(), ts(effectiveAt), tsNullable(expiresAt),
			tsNullable(releasedAt), rootHoldId, previousRevisionId, revisionNo, tsNullable(endedAt), endReason,
			account.ownerId(), ts(createdAt), ts(createdAt));
	}

	private java.sql.Timestamp ts(Instant instant) {
		return java.sql.Timestamp.from(instant);
	}

	private java.sql.Timestamp tsNullable(Instant instant) {
		return instant == null ? null : ts(instant);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("余额快照并发测试屏障超时。");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("余额快照并发测试被中断。", exception);
		}
	}

	private record UserFixture(UUID userId, String email) {
	}

	private record AccountFixture(
		UUID accountId,
		UUID ownerId,
		UUID primaryLedgerId,
		UUID systemLedgerId,
		String currency) {
	}

	private record TransactionFixture(UUID transactionId) {
	}
}
