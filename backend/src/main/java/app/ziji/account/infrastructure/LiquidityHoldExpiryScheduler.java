package app.ziji.account.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.LiquidityHoldExpiryFinalizer;
import app.ziji.account.application.LiquidityHoldExpiryFinalizerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** LiquidityHold 自动过期调度入口；只协调调度、锁与运行记录，事实转换留在 application 层。 */
public class LiquidityHoldExpiryScheduler {

	private static final Logger LOG = LoggerFactory.getLogger(LiquidityHoldExpiryScheduler.class);
	private static final String FAILURE_CODE = "FINALIZER_FAILED";

	private final LiquidityHoldExpiryFinalizer finalizer;
	private final PostgresLiquidityHoldExpiryRunStore runs;
	private final LiquidityHoldExpiryFinalizerProperties properties;
	private final Clock clock;
	private final TransactionTemplate runTransaction;
	private final TransactionTemplate runRecordTransaction;
	private final TransactionTemplate businessTransaction;

	public LiquidityHoldExpiryScheduler(
		LiquidityHoldExpiryFinalizer finalizer,
		PostgresLiquidityHoldExpiryRunStore runs,
		LiquidityHoldExpiryFinalizerProperties properties,
		PlatformTransactionManager transactionManager,
		Clock clock) {
		if (finalizer == null || runs == null || properties == null || transactionManager == null || clock == null) {
			throw new IllegalArgumentException("流动性占用过期调度依赖不能为空。");
		}
		properties.validate();
		this.finalizer = finalizer;
		this.runs = runs;
		this.properties = properties;
		this.clock = clock;
		this.runTransaction = new TransactionTemplate(transactionManager);
		this.runTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.runRecordTransaction = new TransactionTemplate(transactionManager);
		this.runRecordTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.businessTransaction = new TransactionTemplate(transactionManager);
		this.businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Scheduled(
		initialDelayString = "${ziji.liquidity-hold.expiry-finalizer.initial-delay}",
		fixedDelayString = "${ziji.liquidity-hold.expiry-finalizer.fixed-delay}")
	public void runScheduled() {
		Instant scheduledAt = clock.instant();
		String correlationId = UUID.randomUUID().toString();
		String previousRequestId = MDC.get("requestId");
		MDC.put("requestId", correlationId);
		try {
			RunResult result = runOnce(scheduledAt, correlationId);
			if ("FAILED".equals(result.status())) {
				LOG.error("LiquidityHold expiry finalizer completed with failure: job={} correlationId={} status={} errorCode={}",
					PostgresLiquidityHoldExpiryRunStore.JOB_NAME, correlationId, result.status(), FAILURE_CODE);
			} else {
				LOG.info("LiquidityHold expiry finalizer completed: job={} correlationId={} status={} candidates={} finalized={}",
					PostgresLiquidityHoldExpiryRunStore.JOB_NAME, correlationId, result.status(),
					result.candidateCount(), result.finalizedCount());
			}
		} catch (RuntimeException exception) {
			// 只记录异常类型；完整异常可能包含 SQL、参数或外部连接细节，不能进入任务日志。
			LOG.error("LiquidityHold expiry finalizer failed: job={} correlationId={} exceptionType={}",
				PostgresLiquidityHoldExpiryRunStore.JOB_NAME, correlationId, exception.getClass().getName());
		} finally {
			if (previousRequestId == null) {
				MDC.remove("requestId");
			} else {
				MDC.put("requestId", previousRequestId);
			}
		}
	}

	RunResult runOnce(Instant scheduledAt, String correlationId) {
		if (scheduledAt == null || correlationId == null || correlationId.isBlank()) {
			throw new IllegalArgumentException("流动性占用过期运行参数无效。");
		}
		return runTransaction.execute(status -> {
			Instant startedAt = clock.instant();
			if (!runs.tryAcquireAdvisoryLock()) {
				Instant completedAt = clock.instant();
				// 跳过结果也必须独立提交，不能被持锁实例的外层事务边界遮蔽。
				runRecordTransaction.executeWithoutResult(recordStatus ->
					runs.insertSkipped(scheduledAt, startedAt, completedAt, 1));
				return new RunResult("SKIPPED", 0, 0);
			}

			// advisory transaction lock 继续由外层事务持有；运行记录独立提交，便于监控观察 RUNNING，且失败不随业务回滚。
			Instant staleBefore = startedAt.minus(properties.getStaleRunAfter());
			runRecordTransaction.executeWithoutResult(recordStatus ->
				runs.markStaleRunning(staleBefore, clock.instant()));
			UUID runId = runRecordTransaction.execute(statusForRecord ->
				runs.insertRunning(scheduledAt, startedAt, 1));
			try {
				LiquidityHoldExpiryFinalizer.Result result = businessTransaction.execute(transactionStatus -> {
					runs.setTransactionLockTimeout(properties.getLockTimeout());
					return finalizer.finalizeExpired(clock.instant(), correlationId, properties.getBatchSize());
				});
				if (result == null) {
					throw new IllegalStateException("流动性占用过期最终化未返回运行结果。");
				}
				runRecordTransaction.executeWithoutResult(recordStatus ->
					runs.markSucceeded(runId, clock.instant()));
				return new RunResult("SUCCEEDED", result.candidateCount(), result.finalizedCount());
			} catch (RuntimeException exception) {
				// 内层事务已回滚 Hold 与 SYSTEM 审计；外层仍可提交 FAILED 运行记录并释放 advisory lock。
				runRecordTransaction.executeWithoutResult(recordStatus ->
					runs.markFailed(runId, FAILURE_CODE, clock.instant()));
				return new RunResult("FAILED", 0, 0);
			}
		});
	}

	record RunResult(String status, int candidateCount, int finalizedCount) {
	}
}
