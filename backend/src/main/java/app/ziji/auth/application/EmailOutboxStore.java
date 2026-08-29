package app.ziji.auth.application;

import java.time.Instant;
import java.util.Optional;

/** EMAIL 消费者的订阅过滤、receipt 抢占、终态和重试持久化边界。 */
public interface EmailOutboxStore {

	Optional<EmailOutboxClaim> claimNext(String consumerName, Instant now, Instant leaseUntil);

	void markSucceeded(EmailOutboxClaim claim, Instant completedAt);

	void markRetryable(EmailOutboxClaim claim, Instant failedAt, Instant nextAttemptAt, String errorCode);

	void markFinal(EmailOutboxClaim claim, Instant failedAt, String errorCode);
}
