package app.ziji.sync.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import app.ziji.ledger.application.LedgerSyncCommandPort;
import app.ziji.ledger.application.LedgerVersionConflictException;
import app.ziji.ledger.application.SyncLedgerCommand;
import app.ziji.ledger.application.SyncLedgerResult;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Sync 编排只验证结果映射；PostgreSQL 原子性由 HTTP 集成测试覆盖。 */
class SyncOperationApplicationServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID TRANSACTION_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
	private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

	@Test
	void versionConflictIsStoredAndReplayedFromTheFirstSafeReference() {
		Store store = new Store(new IdempotencyRecordStore.Acquisition.Acquired(UUID.randomUUID()));
		Ledger ledger = command -> { throw new LedgerVersionConflictException(TRANSACTION_ID, 7); };
		SyncOperationApplicationService service = service(store, ledger);

		SyncOperationApplicationService.Result first = service.apply(USER_ID, operation(), "req-first");
		assertEquals("CONFLICT", first.status());
		assertEquals(7L, versionConflict(first).get("currentVersion"));
		assertEquals("\"7\"", versionConflict(first).get("currentEtag"));
		assertEquals("/api/v1/transactions/" + TRANSACTION_ID, versionConflict(first).get("resourceLocation"));
		assertEquals(IdempotencyResponse.VersionConflictReference.class, store.completed.reference().getClass());

		store.acquisition = new IdempotencyRecordStore.Acquisition.Replay(store.completed);
		SyncOperationApplicationService.Result replay = service.apply(USER_ID, operation(), "req-replay");
		assertEquals("DUPLICATE", replay.status());
		assertEquals(versionConflict(first), versionConflict(replay));
		assertFalse(replay.error().containsKey("currentResource"));
	}

	@Test
	void hashUsesTheActualCollectionRouteAndTheFrozenOperationPayload() {
		Store store = new Store(new IdempotencyRecordStore.Acquisition.Acquired(UUID.randomUUID()));
		SyncOperationApplicationService service = service(store, command -> new SyncLedgerResult(TRANSACTION_ID, 1));

		service.apply(USER_ID, operation(), "req-hash");

		assertEquals(IdempotencyRequestHasher.hash("POST", "application/json", "/api/v1/sync/operations",
			operation().hashPayload(), null), store.request.requestHash());
		assertEquals("applySyncOperations", store.request.operationId());
	}

	@Test
	void keyReuseAndTransientIdempotencyStatesNeverExposeAResource() {
		assertResult(new IdempotencyRecordStore.Acquisition.KeyReused(), "REJECTED", "IDEMPOTENCY_KEY_REUSED", 422);
		assertResult(new IdempotencyRecordStore.Acquisition.InProgress(), "RETRYABLE", "IDEMPOTENCY_REQUEST_IN_PROGRESS", 409);
		assertResult(new IdempotencyRecordStore.Acquisition.SafeReplayUnavailable(), "RETRYABLE", "INTERNAL_ERROR", 500);
	}

	private static void assertResult(IdempotencyRecordStore.Acquisition acquisition, String status, String code, int errorStatus) {
		SyncOperationApplicationService.Result result = service(new Store(acquisition), command -> {
			throw new AssertionError("非 acquired 状态不得执行业务写入");
		}).apply(USER_ID, operation(), "req-state");
		assertEquals(status, result.status());
		assertEquals(code, result.error().get("code"));
		assertEquals(errorStatus, result.error().get("status"));
		assertEquals(null, result.entityId());
		assertEquals("RETRYABLE".equals(status) ? 5 : null, result.retryAfterSeconds());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> versionConflict(SyncOperationApplicationService.Result result) {
		return (Map<String, Object>) result.error().get("versionConflict");
	}

	private static SyncOperationApplicationService service(Store store, Ledger ledger) {
		return new SyncOperationApplicationService(
			new UnifiedIdempotencyService(new Transactions(), store, anonymousHasher(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)), ledger);
	}

	private static IdempotencyAnonymousSubjectHasher anonymousHasher() {
		return email -> IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
	}

	private static SyncOperationApplicationService.Operation operation() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("operationId", OPERATION_ID);
		payload.put("entityType", "TRANSACTION");
		payload.put("operationType", "CREATE");
		payload.put("entityId", TRANSACTION_ID);
		payload.put("baseVersion", null);
		payload.put("payloadVersion", 1);
		payload.put("payload", Map.of("type", "INCOME", "amount", IdempotencyRequestHasher.decimal("12.00")));
		return new SyncOperationApplicationService.Operation(OPERATION_ID, "sync-operation-key-001", TRANSACTION_ID, null, 1,
			new SyncLedgerCommand.Income(USER_ID, TRANSACTION_ID, UUID.randomUUID(), UUID.randomUUID(),
				new Money(new BigDecimal("12.00"), CurrencyCode.CNY), Instant.EPOCH, LocalDate.of(2026, 8, 16),
				"Asia/Shanghai", null, null), payload);
	}

	@FunctionalInterface
	private interface Ledger extends LedgerSyncCommandPort {
	}

	private static final class Transactions implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class Store implements IdempotencyRecordStore {
		private Acquisition acquisition;
		private IdempotencyRequest request;
		private IdempotencyResponse completed;

		private Store(Acquisition acquisition) {
			this.acquisition = acquisition;
		}

		@Override
		public Acquisition acquire(IdempotencyRequest value, Instant now) {
			request = value;
			return acquisition;
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			completed = response;
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
			return 0;
		}
	}
}
