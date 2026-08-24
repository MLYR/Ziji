package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import app.ziji.account.application.AccountStore;
import app.ziji.account.application.LiquidityHoldExpiryFinalizer;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.account.infrastructure.LiquidityHoldExpiryScheduler;
import app.ziji.account.infrastructure.LiquidityHoldExpiryFinalizerProperties;
import app.ziji.account.infrastructure.PostgresLiquidityHoldExpiryRunStore;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** BE-ACC-008：真实 Spring 调度协调、V006 运行记录和 PostgreSQL advisory lock 验收。 */
@SpringBootTest(properties = {
	"ziji.liquidity-hold.expiry-finalizer.enabled=true",
	"ziji.liquidity-hold.expiry-finalizer.initial-delay=PT1H",
	"ziji.liquidity-hold.expiry-finalizer.fixed-delay=PT1H",
	"ziji.liquidity-hold.expiry-finalizer.lock-timeout=PT1S",
	"ziji.liquidity-hold.expiry-finalizer.batch-size=2"
})
@ActiveProfiles("test")
class LiquidityHoldExpirySchedulingTests extends PostgresIntegrationTestSupport {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private LiquidityHoldExpiryScheduler scheduler;

	@Autowired
	private LiquidityHoldExpiryFinalizerProperties properties;

	@Autowired
	private PostgresLiquidityHoldExpiryRunStore runs;

	@Autowired
	private LiquidityHoldStore holds;

	@Autowired
	private AccountStore accounts;

	@Autowired
	private AuditLogWritePort auditLogs;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanSchedulingFacts() {
		// 调度专项测试使用真实表清理；不删除审计或成员历史，避免触发生产 append-only 约束。
		jdbc.execute("""
			TRUNCATE TABLE scheduled_job_runs, audit_logs, liquidity_holds, account_inclusion_settings,
				account_members, ledger_accounts, accounts, users CASCADE
			""");
		// Spring 测试上下文复用配置 Bean；每个测试恢复锁等待基线，避免锁超时用例留下顺序依赖。
		properties.setLockTimeout(Duration.ofSeconds(1));
		properties.setBatchSize(2);
	}

	@Test
	void twoSchedulersCompeteOnRealAdvisoryLockAndOnlyWinnerScans() throws Exception {
		UserFixture user = user("lock");
		UUID accountId = account(user.userId(), "多实例锁账户");
		Clock fixedClock = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
		Instant expiresAt = fixedClock.instant().minusSeconds(1);
		UUID holdId = seedHold(accountId, user.userId(), expiresAt.minusSeconds(30), expiresAt);
		BlockingAudit blockedAudit = new BlockingAudit(auditLogs, jdbc);
		LiquidityHoldExpiryFinalizer firstFinalizer = new LiquidityHoldExpiryFinalizer(
			accounts, holds, blockedAudit, transactions, fixedClock);
		LiquidityHoldExpiryScheduler firstScheduler = new LiquidityHoldExpiryScheduler(
			firstFinalizer, runs, properties, transactionManager, fixedClock);
		LiquidityHoldExpiryScheduler secondScheduler = new LiquidityHoldExpiryScheduler(
			new LiquidityHoldExpiryFinalizer(accounts, holds, auditLogs, transactions, fixedClock),
			runs, properties, transactionManager, fixedClock);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> winner = executor.submit(firstScheduler::runScheduled);
			assertTrue(blockedAudit.entered.await(10, TimeUnit.SECONDS), "advisory lock winner 未进入 Hold 审计栅栏");
			assertEquals(1, count("""
				SELECT count(*) FROM scheduled_job_runs
				WHERE job_name = ? AND status = 'RUNNING' AND completed_at IS NULL
				""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));

			Future<?> loser = executor.submit(secondScheduler::runScheduled);
			loser.get(10, TimeUnit.SECONDS);
			assertEquals(1, count("""
				SELECT count(*) FROM scheduled_job_runs
				WHERE job_name = ? AND status = 'SKIPPED' AND error_code = 'ADVISORY_LOCK_NOT_ACQUIRED'
				""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
			assertEquals(1, version(holdId));
			assertEquals(0, auditCount(holdId));

			blockedAudit.release.countDown();
			winner.get(15, TimeUnit.SECONDS);
			assertEquals(2, version(holdId));
			assertEquals(1, auditCount(holdId));
			assertEquals(1, count("""
				SELECT count(*) FROM scheduled_job_runs
				WHERE job_name = ? AND status = 'SUCCEEDED' AND attempt_count = 1
				""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
			assertEquals(1, count("""
				SELECT count(*) FROM scheduled_job_runs
				WHERE job_name = ? AND status = 'SKIPPED'
				""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
			String requestId = jdbc.queryForObject(
				"SELECT request_id FROM audit_logs WHERE resource_id = ?", String.class, holdId);
			assertNotNull(requestId);
			assertTrue(requestId.matches("[0-9a-f-]{36}"));
			String metadata = jdbc.queryForObject(
				"SELECT metadata::text FROM audit_logs WHERE resource_id = ?", String.class, holdId);
			assertTrue(metadata.contains("holdId"));
			assertTrue(!metadata.contains("10.00"));
		} finally {
			blockedAudit.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "advisory lock 竞态线程未清理");
		}
	}

	@Test
	void staleRunningIsReconciledAfterAdvisoryLockBeforeNewRunStarts() {
		UserFixture user = user("stale-running");
		UUID accountId = account(user.userId(), "陈旧运行账户");
		Clock fixedClock = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
		Instant expiresAt = fixedClock.instant().minusSeconds(1);
		UUID holdId = seedHold(accountId, user.userId(), expiresAt.minusSeconds(30), expiresAt);
		UUID staleRunId = UUID.randomUUID();
		Instant staleStartedAt = fixedClock.instant().minusSeconds(20 * 60);
		transactions.required(() -> jdbc.update("""
			INSERT INTO scheduled_job_runs (
				id, job_name, scheduled_at, status, attempt_count, error_code, started_at, completed_at
			) VALUES (?, ?, ?, 'RUNNING', 1, NULL, ?, NULL)
			""", staleRunId, PostgresLiquidityHoldExpiryRunStore.JOB_NAME,
			timestamp(staleStartedAt), timestamp(staleStartedAt)));

		LiquidityHoldExpiryScheduler fixedScheduler = new LiquidityHoldExpiryScheduler(
			new LiquidityHoldExpiryFinalizer(accounts, holds, auditLogs, transactions, fixedClock),
			runs, properties, transactionManager, fixedClock);
		fixedScheduler.runScheduled();

		Map<String, Object> staleRun = jdbc.queryForMap(
			"SELECT status, error_code, completed_at FROM scheduled_job_runs WHERE id = ?", staleRunId);
		assertEquals("FAILED", staleRun.get("status"));
		assertEquals("RUN_INTERRUPTED", staleRun.get("error_code"));
		assertNotNull(staleRun.get("completed_at"));
		assertEquals(2, version(holdId));
		assertEquals("EXPIRED", endReason(holdId));
		assertEquals(1, auditCount(holdId));
		assertEquals(1, count("""
			SELECT count(*) FROM scheduled_job_runs
			WHERE job_name = ? AND status = 'SUCCEEDED'
			""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
	}

	@Test
	void failingAuditPersistsFailedRunRollsBackHoldAndAllowsNextRunToRetry() {
		UserFixture user = user("failure");
		UUID accountId = account(user.userId(), "失败重试账户");
		Instant expiresAt = Instant.now().minusSeconds(1);
		UUID holdId = seedHold(accountId, user.userId(), expiresAt.minusSeconds(30), expiresAt);
		AuditLogWritePort failingAudit = entry -> {
			throw new IllegalStateException("测试审计失败");
		};
		LiquidityHoldExpiryScheduler failingScheduler = new LiquidityHoldExpiryScheduler(
			new LiquidityHoldExpiryFinalizer(
				accounts, holds, failingAudit, transactions, Clock.systemUTC()),
			runs, properties, transactionManager, Clock.systemUTC());

		failingScheduler.runScheduled();

		assertEquals(1, count("""
			SELECT count(*) FROM scheduled_job_runs
			WHERE job_name = ? AND status = 'FAILED' AND error_code = 'FINALIZER_FAILED' AND attempt_count = 1
			""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
		assertEquals(1, version(holdId));
		assertNull(endReason(holdId));
		assertEquals(0, auditCount(holdId));

		scheduler.runScheduled();

		assertEquals(2, version(holdId));
		assertEquals("EXPIRED", endReason(holdId));
		assertEquals(1, auditCount(holdId));
		assertEquals(1, count("""
			SELECT count(*) FROM scheduled_job_runs
			WHERE job_name = ? AND status = 'SUCCEEDED'
			""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));
	}

	@Test
	void accountLockTimeoutFailsRunReleasesAdvisoryLockAndAllowsRetry() throws Exception {
		UserFixture user = user("lock-timeout");
		UUID accountId = account(user.userId(), "锁等待超时账户");
		Clock fixedClock = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
		Instant expiresAt = fixedClock.instant().minusSeconds(1);
		UUID holdId = seedHold(accountId, user.userId(), expiresAt.minusSeconds(30), expiresAt);
		properties.setLockTimeout(Duration.ofSeconds(1));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection blocker = dataSource.getConnection()) {
			blocker.setAutoCommit(false);
			try (PreparedStatement statement = blocker.prepareStatement(
				"SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
				statement.setObject(1, accountId);
				try (var result = statement.executeQuery()) {
					assertTrue(result.next());
				}
			}
			int blockerPid;
			try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_backend_pid()")) {
				try (var result = statement.executeQuery()) {
					result.next();
					blockerPid = result.getInt(1);
				}
			}

			LiquidityHoldExpiryScheduler fixedScheduler = new LiquidityHoldExpiryScheduler(
				new LiquidityHoldExpiryFinalizer(accounts, holds, auditLogs, transactions, fixedClock),
				runs, properties, transactionManager, fixedClock);
			Future<?> blocked = executor.submit(fixedScheduler::runScheduled);
			assertTrue(awaitAccountLockWait(blockerPid), "未观察到锁等待超时测试的 PostgreSQL 行锁竞争");
			blocked.get(10, TimeUnit.SECONDS);
			assertEquals(1, version(holdId));
			assertEquals(0, auditCount(holdId));
			assertEquals(1, count("""
				SELECT count(*) FROM scheduled_job_runs
				WHERE job_name = ? AND status = 'FAILED' AND error_code = 'FINALIZER_FAILED'
				""", PostgresLiquidityHoldExpiryRunStore.JOB_NAME));

			blocker.rollback();
			fixedScheduler.runScheduled();
			assertEquals(2, version(holdId));
			assertEquals("EXPIRED", endReason(holdId));
			assertEquals(1, auditCount(holdId));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "锁等待超时线程未清理");
		}
	}

	private UUID seedHold(UUID accountId, UUID createdBy, Instant effectiveAt, Instant expiresAt) {
		UUID holdId = UUID.randomUUID();
		transactions.required(() -> jdbc.update("""
			INSERT INTO liquidity_holds (
				id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
				updated_at, version
			) VALUES (?, ?, 'FROZEN', 10.00, 'CNY', ?, ?, NULL, 'MANUAL', '调度测试敏感 note',
				?, NULL, 1, NULL, NULL, ?, ?, ?, 1)
			""", holdId, accountId, timestamp(effectiveAt), timestamp(expiresAt), holdId, createdBy,
			timestamp(effectiveAt), timestamp(effectiveAt)));
		return holdId;
	}

	private UUID account(UUID createdBy, String name) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now().minusSeconds(60);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (
					id, account_class, account_type, name, institution, currency, note, status, archived_at,
					created_by, created_at, updated_at, version
				) VALUES (?, 'ASSET', 'BANK', ?, NULL, 'CNY', NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, name, createdBy, timestamp(now), timestamp(now));
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
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
		});
		return accountId;
	}

	private UserFixture user(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "expiry-scheduling-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now().minusSeconds(60);
		jdbc.update("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, ?, 'test-only-hash', 1, '调度测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
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

	private boolean awaitAccountLockWait(int blockingPid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiters = jdbc.queryForObject("""
				SELECT count(*)
				FROM pg_stat_activity waiting
				WHERE waiting.datname = current_database()
				  AND waiting.wait_event_type = 'Lock'
				  AND ? = ANY(pg_blocking_pids(waiting.pid))
				  AND waiting.query ILIKE '%accounts%'
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

	private record UserFixture(UUID userId) {
	}

	private static final class BlockingAudit implements AuditLogWritePort {

		private final AuditLogWritePort delegate;
		private final JdbcTemplate jdbc;
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		private BlockingAudit(AuditLogWritePort delegate, JdbcTemplate jdbc) {
			this.delegate = delegate;
			this.jdbc = jdbc;
		}

		@Override
		public void append(AuditLogEntry entry) {
			if (entry.actorType() == ActorType.SYSTEM) {
				jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
				entered.countDown();
				try {
					if (!release.await(15, TimeUnit.SECONDS)) {
						throw new AssertionError("调度审计栅栏释放超时");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("调度竞态线程被中断", exception);
				}
			}
			delegate.append(entry);
		}
	}
}
