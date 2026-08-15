package app.ziji.shared.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 统一幂等 application 编排测试：只有锁定成功的请求执行一次业务工作，其他状态不重写记录。 */
class UnifiedIdempotencyServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

	@Test
	void acquiredWorkCompletesInTheSameRequiredTransaction() {
		FakeTransactionRunner transactions = new FakeTransactionRunner();
		FakeStore store = new FakeStore(new IdempotencyRecordStore.Acquisition.Acquired(UUID.randomUUID()));
		UnifiedIdempotencyService service = service(transactions, store);

		IdempotencyExecution<String> result = service.executeAuthenticated(
			UUID.randomUUID(), 1, "postTransaction", key(), hash(),
			() -> IdempotencyWorkResult.completed("created", IdempotencyResponse.succeededEmpty(201)));

		assertEquals(IdempotencyExecution.Status.EXECUTED, result.status());
		assertEquals("created", result.value());
		assertEquals(1, transactions.calls);
		assertEquals(1, store.acquires);
		assertEquals(1, store.completes);
	}

	@Test
	void replayConflictAndInProgressNeverExecuteBusinessWork() {
		assertDoesNotRun(new IdempotencyRecordStore.Acquisition.Replay(IdempotencyResponse.succeededEmpty(200)),
			IdempotencyExecution.Status.REPLAYED);
		assertDoesNotRun(new IdempotencyRecordStore.Acquisition.KeyReused(), IdempotencyExecution.Status.KEY_REUSED);
		assertDoesNotRun(new IdempotencyRecordStore.Acquisition.InProgress(), IdempotencyExecution.Status.REQUEST_IN_PROGRESS);
		assertDoesNotRun(new IdempotencyRecordStore.Acquisition.SafeReplayUnavailable(),
			IdempotencyExecution.Status.SAFE_REPLAY_UNAVAILABLE);
	}

	@Test
	void lockTimeoutIsAStableInProgressResultAndDoesNotRetryTheWork() {
		FakeStore store = new FakeStore(null);
		store.lockTimeout = true;
		UnifiedIdempotencyService service = service(new FakeTransactionRunner(), store);

		IdempotencyExecution<String> result = service.executeAuthenticated(
			UUID.randomUUID(), 1, "postTransaction", key(), hash(),
			() -> { throw new AssertionError("must not run"); });

		assertEquals(IdempotencyExecution.Status.REQUEST_IN_PROGRESS, result.status());
		assertEquals(5, result.retryAfterSeconds());
		assertEquals(1, store.acquires);
	}

	@Test
	void malformedOrNullWorkFailsBeforeChangingTheStore() {
		FakeStore store = new FakeStore(new IdempotencyRecordStore.Acquisition.Acquired(UUID.randomUUID()));
		UnifiedIdempotencyService service = service(new FakeTransactionRunner(), store);

		assertThrows(IdempotencyValidationException.class, () -> service.execute(null, () -> null));
		assertThrows(IdempotencyValidationException.class, () -> service.executeAuthenticated(
			UUID.randomUUID(), 1, "postTransaction", "short", hash(),
			() -> IdempotencyWorkResult.completed("never", IdempotencyResponse.succeededEmpty(201))));
		assertEquals(0, store.acquires);
		assertThrows(IdempotencyInfrastructureException.class, () -> service.executeAuthenticated(
			UUID.randomUUID(), 1, "postTransaction", key(), hash(), () -> null));
		assertEquals(1, store.acquires);
		assertEquals(0, store.completes);
	}

	@Test
	void cleanupUsesTheSameTransactionBoundaryAndEnforcesABoundedBatch() {
		FakeTransactionRunner transactions = new FakeTransactionRunner();
		FakeStore store = new FakeStore(new IdempotencyRecordStore.Acquisition.InProgress());
		store.deleted = 2;
		UnifiedIdempotencyService service = service(transactions, store);

		assertEquals(2, service.deleteExpiredTerminalRecords(2));
		assertEquals(1, store.cleanupCalls);
		assertThrows(IdempotencyValidationException.class, () -> service.deleteExpiredTerminalRecords(0));
		assertFalse(service.toString().contains(key()));
	}

	private static void assertDoesNotRun(
		IdempotencyRecordStore.Acquisition acquisition,
		IdempotencyExecution.Status expected) {
		FakeStore store = new FakeStore(acquisition);
		UnifiedIdempotencyService service = service(new FakeTransactionRunner(), store);
		IdempotencyExecution<String> result = service.executeAuthenticated(
			UUID.randomUUID(), 1, "postTransaction", key(), hash(),
			() -> { throw new AssertionError("must not run"); });
		assertEquals(expected, result.status());
		assertEquals(0, store.completes);
	}

	private static UnifiedIdempotencyService service(FakeTransactionRunner transactions, FakeStore store) {
		return new UnifiedIdempotencyService(transactions, store,
			email -> IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(2, new byte[32]), null),
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static String key() {
		return "idempotency-key-0001";
	}

	private static String hash() {
		return "a".repeat(64);
	}

	private static final class FakeTransactionRunner implements TransactionRunner {
		private int calls;

		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			calls++;
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			calls++;
			action.run();
		}
	}

	private static final class FakeStore implements IdempotencyRecordStore {
		private final Acquisition acquisition;
		private int acquires;
		private int completes;
		private int cleanupCalls;
		private int deleted;
		private boolean lockTimeout;

		private FakeStore(Acquisition acquisition) {
			this.acquisition = acquisition;
		}

		@Override
		public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquires++;
			if (lockTimeout) {
				throw new IdempotencyLockTimeoutException(new RuntimeException("test lock"));
			}
			return acquisition;
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			completes++;
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
			cleanupCalls++;
			return deleted;
		}
	}
}
