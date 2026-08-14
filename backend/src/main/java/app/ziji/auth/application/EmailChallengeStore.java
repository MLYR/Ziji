package app.ziji.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;

/** 挑战事实存储端口；验证更新由实现使用行锁和条件更新保证一次性语义。 */
public interface EmailChallengeStore {

	void replaceActive(String normalizedEmail, EmailChallengePurpose purpose, Instant now);

	void insert(EmailChallenge challenge);

	Optional<EmailChallenge> findLatestForUpdate(String normalizedEmail, EmailChallengePurpose purpose);

	void markExpired(UUID challengeId, Instant now);

	boolean consume(UUID challengeId, Instant now);

	boolean recordFailedAttempt(UUID challengeId, Instant now);
}
