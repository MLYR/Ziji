package app.ziji.account.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

/** V006 scheduled_job_runs 适配器；advisory transaction lock 与本次运行共享同一个外层事务。 */
public class PostgresLiquidityHoldExpiryRunStore {

	public static final String JOB_NAME = "LIQUIDITY_HOLD_EXPIRY_FINALIZER";
	private static final String STALE_RUN_ERROR_CODE = "RUN_INTERRUPTED";
	private static final int MAX_SCHEDULED_AT_COLLISION_RETRIES = 1_000;
	private static final long SCHEDULED_AT_COLLISION_INCREMENT_NANOS = 1_000L;

	// 固定 bigint 只服务这一项任务；transaction-level advisory lock 会随外层运行事务自动释放。
	private static final long ADVISORY_LOCK_KEY = 0x5A494A484F4C444EL;

	private static final String TRY_ADVISORY_LOCK_SQL = """
		SELECT pg_try_advisory_xact_lock(CAST(? AS bigint)) AS acquired
		""";

	private static final String SET_LOCAL_LOCK_TIMEOUT_SQL = """
		SELECT set_config('lock_timeout', ?, true)
		""";

	private static final String INSERT_RUNNING_SQL = """
		INSERT INTO scheduled_job_runs (
			id, job_name, scheduled_at, status, attempt_count, error_code, started_at, completed_at
		) VALUES (?, ?, CAST(? AS timestamptz), 'RUNNING', ?, NULL, CAST(? AS timestamptz), NULL)
		ON CONFLICT (job_name, scheduled_at) DO NOTHING
		""";

	private static final String INSERT_SKIPPED_SQL = """
		INSERT INTO scheduled_job_runs (
			id, job_name, scheduled_at, status, attempt_count, error_code, started_at, completed_at
		) VALUES (?, ?, CAST(? AS timestamptz), 'SKIPPED', ?, 'ADVISORY_LOCK_NOT_ACQUIRED',
			CAST(? AS timestamptz), CAST(? AS timestamptz))
		ON CONFLICT (job_name, scheduled_at) DO NOTHING
		""";

	private static final String MARK_SUCCEEDED_SQL = """
		UPDATE scheduled_job_runs
		SET status = 'SUCCEEDED', error_code = NULL, completed_at = CAST(? AS timestamptz)
		WHERE id = ? AND job_name = ? AND status = 'RUNNING'
		""";

	private static final String MARK_FAILED_SQL = """
		UPDATE scheduled_job_runs
		SET status = 'FAILED', error_code = ?, completed_at = CAST(? AS timestamptz)
		WHERE id = ? AND job_name = ? AND status = 'RUNNING'
		""";

	private static final String MARK_STALE_RUNNING_SQL = """
		UPDATE scheduled_job_runs
		SET status = 'FAILED', error_code = ?, completed_at = CAST(? AS timestamptz)
		WHERE job_name = ? AND status = 'RUNNING' AND completed_at IS NULL
			AND started_at < CAST(? AS timestamptz)
		""";

	private final DSLContext dsl;

	public PostgresLiquidityHoldExpiryRunStore(DSLContext dsl) {
		if (dsl == null) {
			throw new IllegalArgumentException("流动性占用过期运行记录数据库入口不能为空。");
		}
		this.dsl = dsl;
	}

	public boolean tryAcquireAdvisoryLock() {
		Record record = dsl.resultQuery(TRY_ADVISORY_LOCK_SQL, ADVISORY_LOCK_KEY).fetchOne();
		return record != null && Boolean.TRUE.equals(record.get("acquired", Boolean.class));
	}

	public void setTransactionLockTimeout(Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("流动性占用过期锁等待上限无效。");
		}
		long timeoutMillis = timeout.toMillis();
		if (timeoutMillis < 1) {
			throw new IllegalArgumentException("流动性占用过期锁等待上限过小。");
		}
		// 仅限制当前业务事务的账户行锁等待；超时后事务回滚，外层 advisory lock 仍能正常释放。
		dsl.resultQuery(SET_LOCAL_LOCK_TIMEOUT_SQL, timeoutMillis + "ms").fetchOne();
	}

	public UUID insertRunning(Instant scheduledAt, Instant startedAt, int attemptCount) {
		for (int collision = 0; collision < MAX_SCHEDULED_AT_COLLISION_RETRIES; collision++) {
			UUID runId = UUID.randomUUID();
			Instant recordScheduledAt = collisionAdjusted(scheduledAt, collision);
			int changed = dsl.execute(
				INSERT_RUNNING_SQL, runId, JOB_NAME, utc(recordScheduledAt), attemptCount, utc(startedAt));
			if (changed == 1) {
				return runId;
			}
		}
		throw new IllegalStateException("流动性占用过期运行记录启动失败。");
	}

	public void insertSkipped(Instant scheduledAt, Instant startedAt, Instant completedAt, int attemptCount) {
		for (int collision = 0; collision < MAX_SCHEDULED_AT_COLLISION_RETRIES; collision++) {
			Instant recordScheduledAt = collisionAdjusted(scheduledAt, collision);
			int changed = dsl.execute(
				INSERT_SKIPPED_SQL, UUID.randomUUID(), JOB_NAME, utc(recordScheduledAt), attemptCount,
				utc(startedAt), utc(completedAt));
			if (changed == 1) {
				return;
			}
		}
		throw new IllegalStateException("流动性占用过期跳过记录写入失败。");
	}

	public int markStaleRunning(Instant staleBefore, Instant completedAt) {
		int changed = dsl.execute(
			MARK_STALE_RUNNING_SQL, STALE_RUN_ERROR_CODE, utc(completedAt), JOB_NAME, utc(staleBefore));
		return Math.max(changed, 0);
	}

	public void markSucceeded(UUID runId, Instant completedAt) {
		int changed = dsl.execute(MARK_SUCCEEDED_SQL, utc(completedAt), runId, JOB_NAME);
		if (changed != 1) {
			throw new IllegalStateException("流动性占用过期运行记录成功收口失败。");
		}
	}

	public void markFailed(UUID runId, String errorCode, Instant completedAt) {
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 80) {
			throw new IllegalArgumentException("流动性占用过期运行错误码无效。");
		}
		int changed = dsl.execute(MARK_FAILED_SQL, errorCode, utc(completedAt), runId, JOB_NAME);
		if (changed != 1) {
			throw new IllegalStateException("流动性占用过期运行记录失败收口失败。");
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		if (instant == null) {
			throw new IllegalArgumentException("流动性占用过期运行时间不能为空。");
		}
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant collisionAdjusted(Instant scheduledAt, int collision) {
		if (scheduledAt == null || collision < 0) {
			throw new IllegalArgumentException("流动性占用过期计划时间参数无效。");
		}
		return scheduledAt.plusNanos(SCHEDULED_AT_COLLISION_INCREMENT_NANOS * collision);
	}
}
