package app.ziji;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyWork;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** BUG-API-003：只在 HTTP 测试中暂停目标幂等请求的 inspect/replay 边界，证明并发终态取得后的撤权复核。 */
@TestConfiguration(proxyBeanMethods = false)
public class IdempotencyInspectRaceTestConfiguration {

	@Bean
	@Primary
	InspectRaceIdempotencyService inspectRaceIdempotencyService(
		TransactionRunner transactionRunner,
		IdempotencyRecordStore recordStore,
		IdempotencyAnonymousSubjectHasher anonymousSubjectHasher,
		Clock clock) {
		return new InspectRaceIdempotencyService(transactionRunner, recordStore, anonymousSubjectHasher, clock);
	}

	public static final class InspectRaceIdempotencyService extends UnifiedIdempotencyService {

		private final ConcurrentMap<GateKey, InspectRaceGate> armed = new ConcurrentHashMap<>();

		private InspectRaceIdempotencyService(
			TransactionRunner transactionRunner,
			IdempotencyRecordStore recordStore,
			IdempotencyAnonymousSubjectHasher anonymousSubjectHasher,
			Clock clock) {
			super(transactionRunner, recordStore, anonymousSubjectHasher, clock);
		}

		public InspectRaceGate arm(String operationId, String idempotencyKey) {
			GateKey gateKey = new GateKey(operationId, idempotencyKey);
			InspectRaceGate gate = new InspectRaceGate(gateKey);
			if (armed.putIfAbsent(gateKey, gate) != null) {
				throw new IllegalStateException("幂等 inspect 竞争栅栏已启用。");
			}
			return gate;
		}

		public void disarm(InspectRaceGate gate) {
			armed.remove(gate.key, gate);
		}

		@Override
		public Optional<IdempotencyExecution<Void>> inspectAuthenticated(
			UUID userId,
			int apiMajorVersion,
			String operationId,
			String idempotencyKey,
			String requestHash) {
			Optional<IdempotencyExecution<Void>> inspection = super.inspectAuthenticated(
				userId, apiMajorVersion, operationId, idempotencyKey, requestHash);
			InspectRaceGate gate = armed.get(new GateKey(operationId, idempotencyKey));
			if (inspection.isEmpty() && gate != null
				&& gate.claimInspectPause()) {
				// 仅暂停目标 HTTP 请求的 inspect 之后阶段，不改变生产幂等或事务实现。
				gate.inspectCompleted().countDown();
				await(gate.release(), "幂等 inspect 竞争栅栏未释放");
			}
			return inspection;
		}

		@Override
		public <T> IdempotencyExecution<T> executeAuthenticated(
			UUID userId,
			int apiMajorVersion,
			String operationId,
			String idempotencyKey,
			String requestHash,
			IdempotencyWork<T> work) {
			IdempotencyExecution<T> execution = super.executeAuthenticated(
				userId, apiMajorVersion, operationId, idempotencyKey, requestHash, work);
			InspectRaceGate gate = armed.get(new GateKey(operationId, idempotencyKey));
			if (execution.status() == IdempotencyExecution.Status.REPLAYED && gate != null
				&& gate.claimReplayPause()) {
				// acquire 已经提交并返回 replay 终态后才暂停，测试可在最终权限证明前撤销 membership。
				gate.acquireCompleted().countDown();
				await(gate.acquireRelease(), "幂等 acquire 竞争栅栏未释放");
			}
			return execution;
		}

		private static void await(CountDownLatch latch, String message) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError(message);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("幂等 inspect 竞争测试线程被中断", exception);
			}
		}
	}

	private record GateKey(String operationId, String idempotencyKey) {
	}

	public static final class InspectRaceGate {

		private final GateKey key;
		private final CountDownLatch inspectCompleted = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch acquireCompleted = new CountDownLatch(1);
		private final CountDownLatch acquireRelease = new CountDownLatch(1);
		private final AtomicBoolean inspectPauseClaimed = new AtomicBoolean();
		private final AtomicBoolean replayPauseClaimed = new AtomicBoolean();

		private InspectRaceGate(GateKey key) {
			this.key = key;
		}

		private boolean claimInspectPause() {
			return inspectPauseClaimed.compareAndSet(false, true);
		}

		private boolean claimReplayPause() {
			return replayPauseClaimed.compareAndSet(false, true);
		}

		public CountDownLatch inspectCompleted() {
			return inspectCompleted;
		}

		public CountDownLatch release() {
			return release;
		}

		public CountDownLatch acquireCompleted() {
			return acquireCompleted;
		}

		public CountDownLatch acquireRelease() {
			return acquireRelease;
		}
	}
}
