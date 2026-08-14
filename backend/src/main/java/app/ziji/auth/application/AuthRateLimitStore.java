package app.ziji.auth.application;

import java.time.Instant;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 认证限流持久化端口；实现必须在 PostgreSQL 中原子占用全部桶。 */
public interface AuthRateLimitStore {

	RateLimitDecision consume(
		EmailChallengePurpose purpose,
		AuthRateLimitSubjects subjects,
		Instant now);
}
