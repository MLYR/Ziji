package app.ziji.shared.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一幂等 application service：前置校验后在同一 REQUIRED 事务内取得记录、执行业务并写入终态。
 * 不包含 HTTP、Servlet、CSRF、鉴权或任何业务事实写入；调用方只能在这些前置检查完成后进入本服务。
 * 类保持可代理，避免 Spring 运行时为应用 service 创建 CGLIB 代理时破坏上下文启动。
 */
public class UnifiedIdempotencyService {

	private final TransactionRunner transactionRunner;
	private final IdempotencyRecordStore recordStore;
	private final IdempotencyAnonymousSubjectHasher anonymousSubjectHasher;
	private final Clock clock;

	public UnifiedIdempotencyService(
		TransactionRunner transactionRunner,
		IdempotencyRecordStore recordStore,
		IdempotencyAnonymousSubjectHasher anonymousSubjectHasher,
		Clock clock) {
		if (transactionRunner == null || recordStore == null || anonymousSubjectHasher == null || clock == null) {
			throw new IdempotencyValidationException("幂等服务依赖不能为空。");
		}
		this.transactionRunner = transactionRunner;
		this.recordStore = recordStore;
		this.anonymousSubjectHasher = anonymousSubjectHasher;
		this.clock = clock;
	}

	public <T> IdempotencyExecution<T> executeAuthenticated(
		UUID userId,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash,
		IdempotencyWork<T> work) {
		return execute(IdempotencyRequest.authenticated(
			userId, apiMajorVersion, operationId, idempotencyKey, requestHash), work);
	}

	/**
	 * 只读识别同作用域已有状态；缺失或租约已可接管时返回 empty，实际 acquire 算法保持不变。
	 */
	public Optional<IdempotencyExecution<Void>> inspectAuthenticated(
		UUID userId,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash) {
		IdempotencyRequest request = IdempotencyRequest.authenticated(
			userId, apiMajorVersion, operationId, idempotencyKey, requestHash);
		return recordStore.inspect(request, clock.instant()).map(UnifiedIdempotencyService::inspectionResult);
	}

	/** 公开 registerUser/resetPassword 使用独立 HMAC 主体，绝不以伪造 userId 回退。 */
	public <T> IdempotencyExecution<T> executeAnonymous(
		String email,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash,
		IdempotencyWork<T> work) {
		IdempotencySubject.Anonymous subject = anonymousSubjectHasher.forEmail(email);
		return execute(IdempotencyRequest.anonymous(
			subject, apiMajorVersion, operationId, idempotencyKey, requestHash), work);
	}

	public <T> IdempotencyExecution<T> execute(IdempotencyRequest request, IdempotencyWork<T> work) {
		if (request == null || work == null) {
			throw new IdempotencyValidationException("幂等执行请求无效。");
		}
		try {
			return transactionRunner.required(() -> executeWithinRequiredTransaction(request, work));
		} catch (IdempotencyLockTimeoutException exception) {
			// 锁等待已由 PostgreSQL 终止并回滚；此处不能轮询或重试业务工作。
			return IdempotencyExecution.inProgress();
		}
	}

	/** 受控清理只处理终态、到期且无业务外键引用的记录，触发器仍是最终安全防线。 */
	public int deleteExpiredTerminalRecords(int maximumRecords) {
		if (maximumRecords < 1 || maximumRecords > 1_000) {
			throw new IdempotencyValidationException("幂等清理批次无效。");
		}
		return transactionRunner.required(() -> recordStore.deleteExpiredTerminalRecords(clock.instant(), maximumRecords));
	}

	private <T> IdempotencyExecution<T> executeWithinRequiredTransaction(
		IdempotencyRequest request,
		IdempotencyWork<T> work) {
		Instant acquiredAt = clock.instant();
		IdempotencyRecordStore.Acquisition acquisition = recordStore.acquire(request, acquiredAt);
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.Replay replay) {
			return IdempotencyExecution.replayed(replay.response());
		}
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.KeyReused) {
			return IdempotencyExecution.keyReused();
		}
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.InProgress) {
			return IdempotencyExecution.inProgress();
		}
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.SafeReplayUnavailable) {
			return IdempotencyExecution.safeReplayUnavailable();
		}
		IdempotencyRecordStore.Acquisition.Acquired acquired =
			(IdempotencyRecordStore.Acquisition.Acquired) acquisition;
		IdempotencyWorkResult<T> result = work.execute();
		if (result == null) {
			throw new IdempotencyInfrastructureException("幂等业务未返回终态。");
		}
		recordStore.complete(acquired.recordId(), result.response(), clock.instant());
		return IdempotencyExecution.executed(result.value(), result.response());
	}

	private static IdempotencyExecution<Void> inspectionResult(IdempotencyRecordStore.Acquisition acquisition) {
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.Replay replay) {
			return IdempotencyExecution.replayed(replay.response());
		}
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.KeyReused) {
			return IdempotencyExecution.keyReused();
		}
		if (acquisition instanceof IdempotencyRecordStore.Acquisition.InProgress) {
			return IdempotencyExecution.inProgress();
		}
		// 只读入口绝不能返回 Acquired；未知或不安全历史统一 fail closed。
		return IdempotencyExecution.safeReplayUnavailable();
	}
}
