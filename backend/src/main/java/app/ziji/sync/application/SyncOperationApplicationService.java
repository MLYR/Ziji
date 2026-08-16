package app.ziji.sync.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerSyncCommandPort;
import app.ziji.ledger.application.LedgerVersionConflictException;
import app.ziji.ledger.application.SyncLedgerCommand;
import app.ziji.ledger.application.SyncLedgerResult;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;

/** 批量上传的逐操作编排；批次不包裹事务，每项复用自身的统一幂等事务。 */
public final class SyncOperationApplicationService {

	private static final String OPERATION_ID = "applySyncOperations";

	private final UnifiedIdempotencyService idempotency;
	private final LedgerSyncCommandPort ledger;

	public SyncOperationApplicationService(UnifiedIdempotencyService idempotency, LedgerSyncCommandPort ledger) {
		if (idempotency == null || ledger == null) throw new IllegalArgumentException("同步上传依赖不能为空。");
		this.idempotency = idempotency;
		this.ledger = ledger;
	}

	public Result apply(UUID userId, Operation operation, String requestId) {
		String hash = IdempotencyRequestHasher.hash("POST", "application/json",
			"/api/v1/sync/operations", operation.hashPayload(), null);
		try {
			IdempotencyExecution<Result> execution = idempotency.executeAuthenticated(
				userId, 1, OPERATION_ID, operation.idempotencyKey(), hash, () -> execute(operation, requestId));
			return switch (execution.status()) {
				case EXECUTED -> execution.value();
				case REPLAYED -> replay(operation.operationId(), execution.response(), requestId);
				case KEY_REUSED -> rejected(operation.operationId(), "IDEMPOTENCY_KEY_REUSED", requestId);
				case REQUEST_IN_PROGRESS -> retryable(operation.operationId(), "IDEMPOTENCY_REQUEST_IN_PROGRESS", 409, requestId);
				case SAFE_REPLAY_UNAVAILABLE -> retryable(operation.operationId(), "INTERNAL_ERROR", 500, requestId);
			};
		} catch (RuntimeException exception) {
			// 统一事务已回滚后才能把未确认的暂时故障暴露为逐项可重试，绝不伪造成功或终态拒绝。
			return retryable(operation.operationId(), "INTERNAL_ERROR", 500, requestId);
		}
	}

	private IdempotencyWorkResult<Result> execute(Operation operation, String requestId) {
		try {
			SyncLedgerResult written = ledger.applySync(operation.command());
			Result result = applied(operation.operationId(), written.transactionId(), written.entityVersion());
			return IdempotencyWorkResult.completed(result, IdempotencyResponse.succeededResource(200, "TRANSACTION",
				written.transactionId(), new IdempotencyResponse.ResourceReference(
					"/api/v1/transactions/" + written.transactionId(), quote(written.entityVersion()), (long) written.entityVersion())));
		} catch (LedgerVersionConflictException conflict) {
			String resourceLocation = "/api/v1/transactions/" + conflict.transactionId();
			Result result = conflict(operation.operationId(), conflict.currentVersion(), quote(conflict.currentVersion()), resourceLocation, requestId);
			return IdempotencyWorkResult.completed(result,
				IdempotencyResponse.failedFinalVersionConflict(409, conflict.currentVersion(), resourceLocation));
		} catch (LedgerCommandValidationException exception) {
			Result result = rejected(operation.operationId(), "BUSINESS_RULE_VIOLATION", requestId);
			return IdempotencyWorkResult.completed(result, IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"));
		}
	}

	private static Result replay(UUID operationId, IdempotencyResponse response, String requestId) {
		if (response != null && response.reference() instanceof IdempotencyResponse.ResourceReference reference
			&& response.resourceId() != null && reference.resourceVersion() != null) {
			return duplicate(operationId, response.resourceId(), Math.toIntExact(reference.resourceVersion()));
		}
		if (response != null && response.reference() instanceof IdempotencyResponse.VersionConflictReference reference) {
			return duplicateConflict(operationId, reference.currentVersion(), reference.currentEtag(), reference.resourceLocation(), requestId);
		}
		if (response != null && response.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			return rejectedDuplicate(operationId, problem.errorCode(), requestId);
		}
		return retryable(operationId, "INTERNAL_ERROR", 500, requestId);
	}

	private static Result applied(UUID operationId, UUID entityId, int entityVersion) {
		return new Result(operationId, "APPLIED", entityId, entityVersion, null, null);
	}

	private static Result duplicate(UUID operationId, UUID entityId, int entityVersion) {
		return new Result(operationId, "DUPLICATE", entityId, entityVersion, null, null);
	}

	private static Result conflict(
		UUID operationId, long currentVersion, String currentEtag, String resourceLocation, String requestId) {
		Map<String, Object> error = problem("VERSION_CONFLICT", 409, requestId);
		error.put("versionConflict", Map.of("currentVersion", currentVersion, "currentEtag", currentEtag,
			"resourceLocation", resourceLocation));
		return new Result(operationId, "CONFLICT", null, null, error, null);
	}

	private static Result duplicateConflict(
		UUID operationId, long currentVersion, String currentEtag, String resourceLocation, String requestId) {
		Result first = conflict(operationId, currentVersion, currentEtag, resourceLocation, requestId);
		return new Result(first.operationId(), "DUPLICATE", null, null, first.error(), null);
	}

	private static Result rejected(UUID operationId, String code, String requestId) {
		return new Result(operationId, "REJECTED", null, null, problem(code, 422, requestId), null);
	}

	private static Result rejectedDuplicate(UUID operationId, String code, String requestId) {
		return new Result(operationId, "DUPLICATE", null, null, problem(code, 422, requestId), null);
	}

	private static Result retryable(UUID operationId, String code, int status, String requestId) {
		return new Result(operationId, "RETRYABLE", null, null, problem(code, status, requestId), 5);
	}

	private static Map<String, Object> problem(String code, int status, String requestId) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("type", "https://ziji.app/problems/" + code.toLowerCase().replace('_', '-'));
		value.put("title", status == 409 ? "Conflict" : status == 500 ? "Internal Server Error" : "Rejected");
		value.put("status", status);
		value.put("code", code);
		value.put("requestId", requestId);
		return value;
	}

	private static String quote(int version) { return "\"" + version + "\""; }

	public record Operation(
		UUID operationId, String idempotencyKey, UUID entityId, Integer baseVersion,
		int payloadVersion, SyncLedgerCommand command, Map<String, Object> hashPayload) {
		public Operation {
			if (operationId == null || idempotencyKey == null || entityId == null || payloadVersion != 1
				|| command == null || hashPayload == null) throw new IllegalArgumentException("同步操作无效。");
			hashPayload = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(hashPayload));
		}
	}

	public record Result(UUID operationId, String status, UUID entityId, Integer entityVersion,
		Map<String, Object> error, Integer retryAfterSeconds) {
	}
}
