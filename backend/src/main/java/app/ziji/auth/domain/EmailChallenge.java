package app.ziji.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 邮箱验证码挑战聚合；只保存 Hash 和状态，不接触验证码明文。
 *
 * <p>状态转换返回新实例，应用层再以数据库条件更新原子落盘。</p>
 */
public final class EmailChallenge {

	public static final Duration VALIDITY = Duration.ofMinutes(10);
	public static final int MAX_ATTEMPTS = 5;

	private final UUID id;
	private final EmailChallengePurpose purpose;
	private final String emailNormalized;
	private final String codeHash;
	private final Instant expiresAt;
	private final int attemptCount;
	private final int maxAttempts;
	private final Instant consumedAt;
	private final Instant invalidatedAt;
	private final EmailChallengeInvalidationReason invalidationReason;
	private final Instant createdAt;

	private EmailChallenge(
		UUID id,
		EmailChallengePurpose purpose,
		String emailNormalized,
		String codeHash,
		Instant expiresAt,
		int attemptCount,
		int maxAttempts,
		Instant consumedAt,
		Instant invalidatedAt,
		EmailChallengeInvalidationReason invalidationReason,
		Instant createdAt) {
		if (id == null || purpose == null) {
			throw new AuthDomainException("验证码挑战标识和用途不能为空。");
		}
		if (emailNormalized == null || emailNormalized.isBlank()) {
			throw new AuthDomainException("验证码挑战邮箱不能为空。");
		}
		if (codeHash == null || codeHash.isBlank()) {
			throw new AuthDomainException("验证码 Hash 不能为空。");
		}
		if (expiresAt == null || createdAt == null
			|| !expiresAt.equals(createdAt.plus(VALIDITY))) {
			throw new AuthDomainException("验证码有效期必须固定为十分钟。");
		}
		if (attemptCount < 0 || attemptCount > MAX_ATTEMPTS || maxAttempts != MAX_ATTEMPTS) {
			throw new AuthDomainException("验证码尝试次数不符合 V1 规则。");
		}
		// 第五次错误必须与 MAX_ATTEMPTS 失效原因同时落盘，避免恢复出可继续消费的边界状态。
		if ((attemptCount == maxAttempts
			&& invalidationReason != EmailChallengeInvalidationReason.MAX_ATTEMPTS)
			|| (invalidationReason == EmailChallengeInvalidationReason.MAX_ATTEMPTS
				&& attemptCount != maxAttempts)) {
			throw new AuthDomainException("验证码达到错误上限时必须标记为失效。");
		}
		if (consumedAt != null && invalidatedAt != null) {
			throw new AuthDomainException("验证码不能同时消费和失效。");
		}
		if ((invalidatedAt == null) != (invalidationReason == null)) {
			throw new AuthDomainException("验证码失效时间和原因必须成对存在。");
		}
		if (consumedAt != null && consumedAt.isBefore(createdAt)) {
			throw new AuthDomainException("验证码消费时间无效。");
		}
		if (invalidatedAt != null && invalidatedAt.isBefore(createdAt)) {
			throw new AuthDomainException("验证码失效时间无效。");
		}
		this.id = id;
		this.purpose = purpose;
		this.emailNormalized = emailNormalized;
		this.codeHash = codeHash;
		this.expiresAt = expiresAt;
		this.attemptCount = attemptCount;
		this.maxAttempts = maxAttempts;
		this.consumedAt = consumedAt;
		this.invalidatedAt = invalidatedAt;
		this.invalidationReason = invalidationReason;
		this.createdAt = createdAt;
	}

	/** 创建十分钟有效、五次错误上限的活动挑战。 */
	public static EmailChallenge issue(
		UUID id,
		EmailChallengePurpose purpose,
		String emailNormalized,
		String codeHash,
		Instant createdAt) {
		if (createdAt == null) {
			throw new AuthDomainException("验证码创建时间不能为空。");
		}
		return new EmailChallenge(id, purpose, emailNormalized, codeHash,
			createdAt.plus(VALIDITY), 0, MAX_ATTEMPTS, null, null, null, createdAt);
	}

	/** 从数据库恢复挑战，仍由领域模型复核 V008 的状态不变量。 */
	public static EmailChallenge restore(
		UUID id,
		EmailChallengePurpose purpose,
		String emailNormalized,
		String codeHash,
		Instant expiresAt,
		int attemptCount,
		int maxAttempts,
		Instant consumedAt,
		Instant invalidatedAt,
		EmailChallengeInvalidationReason invalidationReason,
		Instant createdAt) {
		return new EmailChallenge(id, purpose, emailNormalized, codeHash, expiresAt,
			attemptCount, maxAttempts, consumedAt, invalidatedAt, invalidationReason, createdAt);
	}

	public EmailChallengeStatus status() {
		if (consumedAt != null) {
			return EmailChallengeStatus.CONSUMED;
		}
		if (invalidationReason != null) {
			return invalidationReason.status();
		}
		return EmailChallengeStatus.ACTIVE;
	}

	public boolean canConsumeAt(Instant now) {
		return now != null
			&& status() == EmailChallengeStatus.ACTIVE
			&& !now.isBefore(createdAt)
			&& now.isBefore(expiresAt)
			&& attemptCount < maxAttempts;
	}

	/** 正确验证码消费后的不可变状态；重复消费会被领域模型拒绝。 */
	public EmailChallenge consumeAt(Instant consumedAt) {
		ensureConsumable(consumedAt);
		return copy(consumedAt, null, null, attemptCount);
	}

	/** 错误验证码计数原子达到第五次时立即进入 MAX_ATTEMPTS。 */
	public EmailChallenge recordFailedAttemptAt(Instant attemptedAt) {
		ensureConsumable(attemptedAt);
		int nextAttemptCount = attemptCount + 1;
		if (nextAttemptCount >= maxAttempts) {
			return copy(null, attemptedAt, EmailChallengeInvalidationReason.MAX_ATTEMPTS, nextAttemptCount);
		}
		return copy(null, null, null, nextAttemptCount);
	}

	public EmailChallenge expireAt(Instant invalidatedAt) {
		ensureActive(invalidatedAt);
		return copy(null, invalidatedAt, EmailChallengeInvalidationReason.EXPIRED, attemptCount);
	}

	public EmailChallenge replaceAt(Instant invalidatedAt) {
		ensureActive(invalidatedAt);
		return copy(null, invalidatedAt, EmailChallengeInvalidationReason.REPLACED, attemptCount);
	}

	public EmailChallenge securityRevokeAt(Instant invalidatedAt) {
		ensureActive(invalidatedAt);
		return copy(null, invalidatedAt, EmailChallengeInvalidationReason.SECURITY_REVOKED, attemptCount);
	}

	private void ensureConsumable(Instant now) {
		if (!canConsumeAt(now)) {
			throw new AuthDomainException("验证码无效或已失效。");
		}
	}

	private void ensureActive(Instant now) {
		if (now == null || status() != EmailChallengeStatus.ACTIVE || now.isBefore(createdAt)) {
			throw new AuthDomainException("验证码无效或已失效。");
		}
	}

	private EmailChallenge copy(
		Instant nextConsumedAt,
		Instant nextInvalidatedAt,
		EmailChallengeInvalidationReason nextReason,
		int nextAttemptCount) {
		return new EmailChallenge(id, purpose, emailNormalized, codeHash, expiresAt,
			nextAttemptCount, maxAttempts, nextConsumedAt, nextInvalidatedAt, nextReason, createdAt);
	}

	public UUID id() {
		return id;
	}

	public EmailChallengePurpose purpose() {
		return purpose;
	}

	public String emailNormalized() {
		return emailNormalized;
	}

	public String codeHash() {
		return codeHash;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public int attemptCount() {
		return attemptCount;
	}

	public int maxAttempts() {
		return maxAttempts;
	}

	public Instant consumedAt() {
		return consumedAt;
	}

	public Instant invalidatedAt() {
		return invalidatedAt;
	}

	public EmailChallengeInvalidationReason invalidationReason() {
		return invalidationReason;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
