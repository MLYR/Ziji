package app.ziji.shared.application;

import java.time.Instant;
import java.util.UUID;

/** PostgreSQL 幂等记录端口；实现必须以原子 UPSERT 和 FOR UPDATE 而不是进程内锁保证并发安全。 */
public interface IdempotencyRecordStore {

	Acquisition acquire(IdempotencyRequest request, Instant now);

	void complete(UUID recordId, IdempotencyResponse response, Instant completedAt);

	int deleteExpiredTerminalRecords(Instant now, int maximumRecords);

	sealed interface Acquisition permits Acquisition.Acquired, Acquisition.Replay,
		Acquisition.KeyReused, Acquisition.InProgress, Acquisition.SafeReplayUnavailable {

		/** 已在当前事务锁住且可以执行一次业务工作。 */
		record Acquired(UUID recordId) implements Acquisition {
		}

		/** 已有安全终态，只可重放其最小安全引用。 */
		record Replay(IdempotencyResponse response) implements Acquisition {
		}

		/** 同 Key 的载荷 Hash 与首次请求不同，不得改写原记录。 */
		record KeyReused() implements Acquisition {
		}

		/** 行锁超时、未过期 PROCESSING 或未到 retry_after 的可重试失败。 */
		record InProgress() implements Acquisition {
		}

		/** V009 前不满足安全响应约束的历史行只能保留，不得被普通服务伪造或重放。 */
		record SafeReplayUnavailable() implements Acquisition {
		}
	}
}
