package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import app.ziji.account.application.AccountStore;
import app.ziji.account.application.AccountBalanceResult;
import app.ziji.account.application.AccountBalanceUseCase;
import app.ziji.account.application.LiquidityHoldCommand;
import app.ziji.account.application.LiquidityHoldCursorCodec;
import app.ziji.account.application.LiquidityHoldExpiryFinalizer;
import app.ziji.account.application.LiquidityHoldService;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.account.application.LiquidityHoldException;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldEndReason;
import app.ziji.account.domain.LiquidityHoldSource;
import app.ziji.account.domain.LiquidityHoldType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** BE-ACC-008：真实 PostgreSQL 验证过期边界、回滚、无副作用和人工生命周期竞态。 */
@SpringBootTest
@ActiveProfiles("test")
class LiquidityHoldExpiryPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant AS_OF = Instant.parse("2026-08-24T12:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private LiquidityHoldStore holds;

	@Autowired
	private AccountBalanceUseCase balanceUseCase;

	@Autowired
	private AuditLogWritePort auditLogs;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private AccountStore accounts;

	@Autowired
	private AccountMembershipReadPort memberships;

	@Autowired
	private LiquidityHoldCursorCodec cursors;

	@BeforeEach
	void cleanFactsCreatedByThisClass() {
		// 审计和成员历史禁止 DELETE；测试数据库用 TRUNCATE 清理夹具，避免全局到期扫描受前一测试影响。
		jdbc.execute("""
			TRUNCATE TABLE audit_logs, liquidity_holds, account_inclusion_settings, account_members,
				ledger_accounts, accounts, users CASCADE
			""");
	}

	@Test
	void postgresFinalizerHonorsBoundaryFutureNullEndedAndVersionConditions() {
		UserFixture user = user("boundary");
		UUID accountId = account(user.userId(), "边界账户", false);
		UUID exactId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF, null, null, 1);
		UUID futureId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.plusSeconds(1), null, null, 1);
		UUID neverId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), null, null, null, 1);
		UUID endedId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(1),
			AS_OF.minusSeconds(1), LiquidityHoldEndReason.RELEASED.name(), 1);
		UUID versionId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(1),
			null, null, 2);

		LiquidityHoldExpiryFinalizer.Result result = finalizer(Clock.fixed(AS_OF, ZoneOffset.UTC), auditLogs)
			.finalizeExpired(AS_OF, "postgres-boundary-correlation", 100);

		assertEquals(2, result.candidateCount());
		assertEquals(2, result.finalizedCount());
		assertEquals("EXPIRED", endReason(exactId));
		assertEquals("EXPIRED", endReason(versionId));
		assertEquals(2, version(exactId));
		assertEquals(3, version(versionId));
		assertEquals(1, version(futureId));
		assertEquals(1, version(neverId));
		assertEquals(1, version(endedId));
		assertEquals(1, auditCount(exactId));
		assertEquals(1, auditCount(versionId));
		assertEquals(0, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE resource_id IN (?, ?, ?)", Integer.class, futureId, neverId, endedId));
		assertNull(jdbc.queryForObject("SELECT released_at FROM liquidity_holds WHERE id = ?", Timestamp.class, exactId));

		// 旧版本条件必须安全返回空结果，不能覆盖数据库中已变化的版本。
		assertTrue(holds.expireIfVersion(accountId, versionId, 2, AS_OF, AS_OF.plusSeconds(1)).isEmpty());
		assertEquals(3, version(versionId));
	}

	@Test
	void postgresFinalizerProcessesOnlyOneBoundedBatchAndContinuesOnNextRun() {
		UserFixture user = user("batch");
		UUID accountId = account(user.userId(), "批次账户", false);
		UUID firstId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(3), null, null, 1);
		UUID secondId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(2), null, null, 1);
		UUID thirdId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(1), null, null, 1);
		LiquidityHoldExpiryFinalizer finalizer = finalizer(
			Clock.fixed(AS_OF.plusSeconds(5), ZoneOffset.UTC), auditLogs);

		LiquidityHoldExpiryFinalizer.Result first = finalizer.finalizeExpired(AS_OF, "postgres-batch-1", 2);
		assertEquals(2, first.candidateCount());
		assertEquals(2, first.finalizedCount());
		assertEquals(2, auditCount(firstId) + auditCount(secondId) + auditCount(thirdId));
		assertEquals(1, version(thirdId));

		LiquidityHoldExpiryFinalizer.Result second = finalizer.finalizeExpired(AS_OF, "postgres-batch-2", 2);
		assertEquals(1, second.candidateCount());
		assertEquals(1, second.finalizedCount());
		assertEquals(2, version(firstId));
		assertEquals(2, version(secondId));
		assertEquals(2, version(thirdId));
		assertEquals(1, auditCount(firstId));
		assertEquals(1, auditCount(secondId));
		assertEquals(1, auditCount(thirdId));
	}

	@Test
	void finalizerLocksAllBatchAccountsInGlobalUuidOrderBeforeProcessingExpiryOrder() throws Exception {
		UserFixture user = user("account-lock-order");
		UUID firstAccount = account(user.userId(), "锁序账户一", false);
		UUID secondAccount = account(user.userId(), "锁序账户二", false);
		UUID lowerAccount = firstAccount.compareTo(secondAccount) < 0 ? firstAccount : secondAccount;
		UUID higherAccount = firstAccount.compareTo(secondAccount) < 0 ? secondAccount : firstAccount;
		seedHold(higherAccount, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(2), null, null, 1);
		seedHold(lowerAccount, user.userId(), AS_OF.minusSeconds(10), AS_OF.minusSeconds(1), null, null, 1);

		BlockingAccountStore blockingAccounts = new BlockingAccountStore(accounts, jdbc);
		LiquidityHoldExpiryFinalizer expiry = new LiquidityHoldExpiryFinalizer(
			blockingAccounts, holds, auditLogs, transactions, Clock.fixed(AS_OF, ZoneOffset.UTC));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<LiquidityHoldExpiryFinalizer.Result> automatic = executor.submit(
				() -> expiry.finalizeExpired(AS_OF, "automatic-account-lock-order", 10));
			assertTrue(blockingAccounts.firstLockEntered.await(10, TimeUnit.SECONDS), "finalizer 未进入首个账户锁栅栏");
			assertEquals(lowerAccount, blockingAccounts.firstLockAccount.get());

			Future<?> ledgerLockOrder = executor.submit(() -> transactions.required(() -> {
				// 模拟多账户账务命令的固定 UUID 锁序；该事务必须在 lowerAccount 上等待，而不是形成反向死锁。
				accounts.findByIdForUpdate(lowerAccount);
				accounts.findByIdForUpdate(higherAccount);
			}));
			assertTrue(awaitLifecycleLockWait(blockingAccounts.backendPid.get()), "未观察到多账户事务等待首个账户行锁");
			blockingAccounts.release.countDown();

			assertEquals(2, automatic.get(15, TimeUnit.SECONDS).finalizedCount());
			ledgerLockOrder.get(15, TimeUnit.SECONDS);
		} finally {
			blockingAccounts.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "账户锁序竞态线程未清理");
		}
	}

	@Test
	void auditFailureRollsBackHoldAndAllowsRetryWithoutPartialFacts() {
		UserFixture user = user("rollback");
		UUID accountId = account(user.userId(), "回滚账户", false);
		UUID holdId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(1), AS_OF, null, null, 1);
		int transactionsBefore = count("SELECT count(*) FROM transactions");
		int ledgerEntriesBefore = count("SELECT count(*) FROM ledger_entries");
		int outboxBefore = count("SELECT count(*) FROM outbox_events");
		int idempotencyBefore = count("SELECT count(*) FROM idempotency_records");

		AuditLogWritePort failingAudit = entry -> {
			throw new IllegalStateException("测试审计失败");
		};
		LiquidityHoldExpiryFinalizer failing = finalizer(
			Clock.fixed(AS_OF.plusSeconds(1), ZoneOffset.UTC), failingAudit);
		assertThrows(IllegalStateException.class,
			() -> failing.finalizeExpired(AS_OF, "postgres-rollback-failure", 10));

		assertNull(endReason(holdId));
		assertEquals(1, version(holdId));
		assertEquals(0, auditCount(holdId));
		assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions"));
		assertEquals(ledgerEntriesBefore, count("SELECT count(*) FROM ledger_entries"));
		assertEquals(outboxBefore, count("SELECT count(*) FROM outbox_events"));
		assertEquals(idempotencyBefore, count("SELECT count(*) FROM idempotency_records"));

		LiquidityHoldExpiryFinalizer retry = finalizer(
			Clock.fixed(AS_OF.plusSeconds(2), ZoneOffset.UTC), auditLogs);
		assertEquals(1, retry.finalizeExpired(AS_OF, "postgres-rollback-retry", 10).finalizedCount());
		assertEquals("EXPIRED", endReason(holdId));
		assertEquals(2, version(holdId));
		assertEquals(1, auditCount(holdId));
	}

	@Test
	void archivedAccountAndRemovedCreatorDoNotBlockSystemFinalization() {
		UserFixture creator = user("archived-creator");
		UserFixture unrelated = user("archived-unrelated");
		UUID accountId = account(creator.userId(), "归档账户", true);
		UUID holdId = seedHold(accountId, creator.userId(), AS_OF.minusSeconds(2), AS_OF, null, null, 1);
		addMembership(accountId, unrelated.userId(), "OWNER", "ACTIVE", AS_OF.minusSeconds(1));
		transactions.required(() -> jdbc.update("""
			UPDATE account_members
			SET status = 'LEFT', ended_at = ?, version = version + 1
			WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
			""", timestamp(AS_OF.minusSeconds(1)), accountId, creator.userId()));

		LiquidityHoldExpiryFinalizer.Result result = finalizer(
			Clock.fixed(AS_OF.plusSeconds(1), ZoneOffset.UTC), auditLogs)
			.finalizeExpired(AS_OF, "postgres-no-membership", 10);

		assertEquals(1, result.finalizedCount());
		assertEquals("EXPIRED", endReason(holdId));
		Map<String, Object> audit = jdbc.queryForMap(
			"SELECT actor_user_id, actor_type, action, reason_code, request_id, metadata::text AS metadata "
				+ "FROM audit_logs WHERE resource_id = ?", holdId);
		assertEquals(null, audit.get("actor_user_id"));
		assertEquals("SYSTEM", audit.get("actor_type"));
		assertEquals("LIQUIDITY_HOLD_EXPIRED", audit.get("action"));
		assertEquals("EXPIRED", audit.get("reason_code"));
		assertEquals("postgres-no-membership", audit.get("request_id"));
		assertTrue(!String.valueOf(audit.get("metadata")).contains("10.00"));
	}

	@Test
	void manualReleaseFirstWinsAndFinalizerReturnsNoConversionAfterRealRowLockWait() throws Exception {
		UserFixture user = user("release-first");
		UUID accountId = account(user.userId(), "人工释放先提交", false);
		Instant manualNow = Instant.parse("2026-08-24T11:59:00Z");
		Instant expiresAt = manualNow.plusSeconds(60);
		UUID holdId = seedHold(accountId, user.userId(), manualNow.minusSeconds(10), expiresAt, null, null, 1);
		BlockingAudit blockedManualAudit = new BlockingAudit(auditLogs, AuditLogWritePort.ActorType.USER, jdbc);
		LiquidityHoldService manual = manualService(blockedManualAudit, Clock.fixed(manualNow, ZoneOffset.UTC));
		LiquidityHoldExpiryFinalizer expiry = finalizer(
			Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC), auditLogs);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<LiquidityHold> release = executor.submit(
				() -> manual.release(user.userId(), accountId, holdId, 1, "manual-release-race"));
			assertTrue(blockedManualAudit.entered.await(10, TimeUnit.SECONDS), "人工 release 未进入审计栅栏");
			Future<LiquidityHoldExpiryFinalizer.Result> automatic = executor.submit(
				() -> expiry.finalizeExpired(expiresAt, "automatic-release-race", 10));
			assertTrue(awaitLifecycleLockWait(blockedManualAudit.backendPid.get()), "未观察到 finalizer 等待人工 release 的 PostgreSQL 行锁");
			blockedManualAudit.release.countDown();
			assertEquals(LiquidityHoldEndReason.RELEASED, release.get(15, TimeUnit.SECONDS).endReason());
			assertEquals(0, automatic.get(15, TimeUnit.SECONDS).finalizedCount());
			assertEquals("RELEASED", endReason(holdId));
			assertEquals(2, version(holdId));
			assertEquals(1, auditCount(holdId));
		} finally {
			blockedManualAudit.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "release 竞态线程未清理");
		}
	}

	@Test
	void manualRevisionFirstWinsAndFinalizerCannotEndSupersededVersion() throws Exception {
		UserFixture user = user("revision-first");
		UUID accountId = account(user.userId(), "人工修订先提交", false);
		Instant manualNow = Instant.parse("2026-08-24T11:59:00Z");
		Instant expiresAt = manualNow.plusSeconds(60);
		UUID holdId = seedHold(accountId, user.userId(), manualNow.minusSeconds(10), expiresAt, null, null, 1);
		BlockingAudit blockedManualAudit = new BlockingAudit(auditLogs, AuditLogWritePort.ActorType.USER, jdbc);
		LiquidityHoldService manual = manualService(blockedManualAudit, Clock.fixed(manualNow, ZoneOffset.UTC));
		LiquidityHoldExpiryFinalizer expiry = finalizer(
			Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC), auditLogs);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<LiquidityHold> revise = executor.submit(() -> manual.revise(
				user.userId(), accountId, holdId, 1,
				new LiquidityHoldCommand(
					LiquidityHoldType.RESERVED, new BigDecimal("11.00"), AccountCurrency.CNY,
					manualNow, expiresAt.plusSeconds(120), "人工修订"),
				"manual-revision-race"));
			assertTrue(blockedManualAudit.entered.await(10, TimeUnit.SECONDS), "人工 revise 未进入审计栅栏");
			Future<LiquidityHoldExpiryFinalizer.Result> automatic = executor.submit(
				() -> expiry.finalizeExpired(expiresAt, "automatic-revision-race", 10));
			assertTrue(awaitLifecycleLockWait(blockedManualAudit.backendPid.get()), "未观察到 finalizer 等待人工 revise 的 PostgreSQL 行锁");
			blockedManualAudit.release.countDown();
			LiquidityHold revised = revise.get(15, TimeUnit.SECONDS);
			assertEquals(null, revised.endReason());
			assertEquals(0, automatic.get(15, TimeUnit.SECONDS).finalizedCount());
			assertEquals("SUPERSEDED", endReason(holdId));
			assertEquals(2, version(holdId));
			UUID currentId = jdbc.queryForObject(
				"SELECT id FROM liquidity_holds WHERE root_hold_id = ? AND ended_at IS NULL", UUID.class, holdId);
			assertNotNull(currentId);
			assertEquals(0, auditCount(holdId));
			assertEquals(1, auditCount(currentId));
			assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE account_id = ?", accountId));
		} finally {
			blockedManualAudit.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "revision 竞态线程未清理");
		}
	}

	@Test
	void revisionSwitchesHistoricalBalanceAtNewEffectiveAtWithoutCreatedAtCutoffOrOverlap() {
		UserFixture user = user("revision-effective-boundary");
		UUID accountId = account(user.userId(), "修订生效边界", false);
		Instant operationAt = AS_OF;
		Instant revisionEffectiveAt = AS_OF.plusSeconds(60);
		UUID holdId = seedHold(accountId, user.userId(), AS_OF.minusSeconds(10), null, null, null, 1);
		LiquidityHoldService manual = manualService(auditLogs, Clock.fixed(operationAt, ZoneOffset.UTC));

		LiquidityHold revised = manual.revise(
			user.userId(), accountId, holdId, 1,
			new LiquidityHoldCommand(
				LiquidityHoldType.RESERVED, new BigDecimal("20.00"), AccountCurrency.CNY,
				revisionEffectiveAt, null, "未来生效修订"),
			"revision-effective-boundary");

		assertEquals(revisionEffectiveAt, revised.effectiveAt());
		assertEquals(revisionEffectiveAt, jdbc.queryForObject(
			"SELECT ended_at FROM liquidity_holds WHERE id = ?", Timestamp.class, holdId).toInstant());
		assertEquals(operationAt, jdbc.queryForObject(
			"SELECT updated_at FROM liquidity_holds WHERE id = ?", Timestamp.class, holdId).toInstant());

		AccountBalanceResult beforeSwitch = balanceUseCase.getBalance(
			user.userId(), accountId, revisionEffectiveAt.minusSeconds(1));
		AccountBalanceResult atSwitch = balanceUseCase.getBalance(user.userId(), accountId, revisionEffectiveAt);
		assertEquals(new BigDecimal("10.00"), beforeSwitch.unavailableAmount());
		assertEquals(new BigDecimal("10.00"), beforeSwitch.unavailableBreakdown().frozen());
		assertEquals(new BigDecimal("20.00"), atSwitch.unavailableAmount());
		assertEquals(new BigDecimal("20.00"), atSwitch.unavailableBreakdown().reserved());
	}

	@Test
	void finalizerFirstWinsAndManualReleaseOrRevisionSeesVersionConflict() throws Exception {
		assertFinalizerFirstManualOperation(false);
		cleanFactsCreatedByThisClass();
		assertFinalizerFirstManualOperation(true);
	}

	private void assertFinalizerFirstManualOperation(boolean revise) throws Exception {
		UserFixture user = user(revise ? "finalizer-first-revise" : "finalizer-first-release");
		UUID accountId = account(user.userId(), revise ? "自动过期先修订" : "自动过期先释放", false);
		Instant manualNow = Instant.parse("2026-08-24T11:59:00Z");
		Instant expiresAt = manualNow.plusSeconds(60);
		UUID holdId = seedHold(accountId, user.userId(), manualNow.minusSeconds(10), expiresAt, null, null, 1);
		BlockingAudit blockedExpiryAudit = new BlockingAudit(auditLogs, AuditLogWritePort.ActorType.SYSTEM, jdbc);
		LiquidityHoldExpiryFinalizer expiry = finalizer(
			Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC), blockedExpiryAudit);
		LiquidityHoldService manual = manualService(auditLogs, Clock.fixed(manualNow, ZoneOffset.UTC));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<LiquidityHoldExpiryFinalizer.Result> automatic = executor.submit(
				() -> expiry.finalizeExpired(expiresAt, "automatic-first", 10));
			assertTrue(blockedExpiryAudit.entered.await(10, TimeUnit.SECONDS), "自动最终化未进入审计栅栏");
			Future<?> manualOperation = executor.submit(() -> {
				if (revise) {
					return manual.revise(
						user.userId(), accountId, holdId, 1,
						new LiquidityHoldCommand(
							LiquidityHoldType.RESERVED, new BigDecimal("12.00"), AccountCurrency.CNY,
							manualNow, expiresAt.plusSeconds(120), "迟到修订"),
						"manual-after-expiry");
				}
				return manual.release(user.userId(), accountId, holdId, 1, "manual-after-expiry");
			});
			assertTrue(awaitLifecycleLockWait(blockedExpiryAudit.backendPid.get()), "未观察到人工操作等待 finalizer 的 PostgreSQL 行锁");
			blockedExpiryAudit.release.countDown();
			assertEquals(1, automatic.get(15, TimeUnit.SECONDS).finalizedCount());
			ExecutionException failure = assertThrows(
				ExecutionException.class, () -> manualOperation.get(15, TimeUnit.SECONDS));
			assertTrue(failure.getCause() instanceof LiquidityHoldException.VersionConflict);
			assertEquals("EXPIRED", endReason(holdId));
			assertNull(jdbc.queryForObject("SELECT released_at FROM liquidity_holds WHERE id = ?", Timestamp.class, holdId));
			assertEquals(2, version(holdId));
			assertEquals(1, auditCount(holdId));
			assertEquals(1, count("SELECT count(*) FROM liquidity_holds WHERE root_hold_id = ?", holdId));
		} finally {
			blockedExpiryAudit.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "finalizer 竞态线程未清理");
		}
	}

	private LiquidityHoldExpiryFinalizer finalizer(Clock clock, AuditLogWritePort audit) {
		return new LiquidityHoldExpiryFinalizer(accounts, holds, audit, transactions, clock);
	}

	private LiquidityHoldService manualService(AuditLogWritePort audit, Clock clock) {
		return new LiquidityHoldService(
			accounts, memberships, holds, cursors, audit, transactions, clock, UUID::randomUUID);
	}

	private UUID seedHold(
		UUID accountId,
		UUID createdBy,
		Instant effectiveAt,
		Instant expiresAt,
		Instant endedAt,
		String endReason,
		int version) {
		UUID holdId = UUID.randomUUID();
		Instant releasedAt = LiquidityHoldEndReason.RELEASED.name().equals(endReason) ? endedAt : null;
		transactions.required(() -> jdbc.update("""
			INSERT INTO liquidity_holds (
				id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
				updated_at, version
			) VALUES (?, ?, 'FROZEN', 10.00, 'CNY', ?, ?, ?, 'MANUAL', '仅用于测试审计脱敏',
				?, NULL, 1, ?, ?, ?, ?, ?, ?)
			""", holdId, accountId, timestamp(effectiveAt), timestampOrNull(expiresAt),
			timestampOrNull(releasedAt), holdId, timestampOrNull(endedAt), endReason, createdBy,
			timestamp(effectiveAt), timestamp(effectiveAt), version));
		return holdId;
	}

	private UUID account(UUID createdBy, String name, boolean archived) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		Instant now = AS_OF.minusSeconds(100);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (
					id, account_class, account_type, name, institution, currency, note, status, archived_at,
					created_by, created_at, updated_at, version
				) VALUES (?, 'ASSET', 'BANK', ?, NULL, 'CNY', NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, name, createdBy, timestamp(now), timestamp(now));
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, createdBy, timestamp(now));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, timestamp(now), createdBy, timestamp(now));
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, timestamp(now));
			if (archived) {
				jdbc.update("""
					UPDATE accounts SET status = 'ARCHIVED', archived_at = ?, updated_at = ?, version = 2
					WHERE id = ?
					""", timestamp(now), timestamp(now), accountId);
			}
		});
		return accountId;
	}

	private void addMembership(UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
			VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
			""", UUID.randomUUID(), accountId, userId, role, status, timestamp(endedAt.minusSeconds(10)),
			"ACTIVE".equals(status) ? null : timestamp(endedAt));
	}

	private UserFixture user(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "expiry-postgres-" + suffix + "-" + userId + "@example.test";
		Instant now = AS_OF.minusSeconds(100);
		jdbc.update("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, ?, 'test-only-hash', 1, '过期测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, email, email, timestamp(now), timestamp(now), timestamp(now));
		return new UserFixture(userId);
	}

	private int version(UUID holdId) {
		return jdbc.queryForObject("SELECT version FROM liquidity_holds WHERE id = ?", Integer.class, holdId);
	}

	private String endReason(UUID holdId) {
		return jdbc.queryForObject("SELECT end_reason FROM liquidity_holds WHERE id = ?", String.class, holdId);
	}

	private int auditCount(UUID holdId) {
		return count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", holdId);
	}

	private int count(String sql, Object... arguments) {
		Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
		return result == null ? 0 : result;
	}

	private boolean awaitLifecycleLockWait(int blockingPid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiters = jdbc.queryForObject("""
				SELECT count(*)
				FROM pg_stat_activity waiting
				WHERE waiting.datname = current_database()
				  AND waiting.wait_event_type = 'Lock'
				  AND ? = ANY(pg_blocking_pids(waiting.pid))
				  AND (waiting.query ILIKE '%accounts%' OR waiting.query ILIKE '%liquidity_holds%')
				""", Integer.class, blockingPid);
			if (waiters != null && waiters > 0) {
				return true;
			}
			Thread.yield();
		}
		return false;
	}

	private Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private Timestamp timestampOrNull(Instant instant) {
		return instant == null ? null : timestamp(instant);
	}

	private record UserFixture(UUID userId) {
	}

	private static final class BlockingAudit implements AuditLogWritePort {

		private final AuditLogWritePort delegate;
		private final ActorType blockedActor;
		private final JdbcTemplate jdbc;
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger backendPid = new AtomicInteger();

		private BlockingAudit(AuditLogWritePort delegate, ActorType blockedActor, JdbcTemplate jdbc) {
			this.delegate = delegate;
			this.blockedActor = blockedActor;
			this.jdbc = jdbc;
		}

		@Override
		public void append(AuditLogEntry entry) {
			if (entry.actorType() == blockedActor) {
				backendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				entered.countDown();
				try {
					if (!release.await(15, TimeUnit.SECONDS)) {
						throw new AssertionError("审计竞态栅栏释放超时");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("审计竞态线程被中断", exception);
				}
			}
			delegate.append(entry);
		}
	}

	private static final class BlockingAccountStore implements AccountStore {

		private final AccountStore delegate;
		private final JdbcTemplate jdbc;
		private final CountDownLatch firstLockEntered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicReference<UUID> firstLockAccount = new AtomicReference<>();
		private final AtomicInteger backendPid = new AtomicInteger();

		private BlockingAccountStore(AccountStore delegate, JdbcTemplate jdbc) {
			this.delegate = delegate;
			this.jdbc = jdbc;
		}

		@Override
		public void insert(Account account) {
			delegate.insert(account);
		}

		@Override
		public Optional<Account> findById(UUID accountId) {
			return delegate.findById(accountId);
		}

		@Override
		public Optional<Account> findByIdForUpdate(UUID accountId) {
			Optional<Account> account = delegate.findByIdForUpdate(accountId);
			if (firstLockAccount.compareAndSet(null, accountId)) {
				backendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				firstLockEntered.countDown();
				try {
					if (!release.await(15, TimeUnit.SECONDS)) {
						throw new AssertionError("账户锁序竞态栅栏释放超时");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("账户锁序竞态线程被中断", exception);
				}
			}
			return account;
		}
	}
}
