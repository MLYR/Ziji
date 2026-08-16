package app.ziji.sync.application;

import java.time.Instant;
import java.util.Optional;

/** 当前消费者的订阅过滤、receipt 抢占、终态和重试持久化边界。 */
public interface SyncOutboxStore {

	Optional<SyncOutboxClaim> claimNext(String consumerName, Instant now, Instant leaseUntil);

	void markSucceeded(SyncOutboxClaim claim, Instant completedAt);

	void markRetryable(SyncOutboxClaim claim, Instant failedAt, Instant nextAttemptAt, String errorCode);

	void markFinal(SyncOutboxClaim claim, Instant failedAt, String errorCode);
}
