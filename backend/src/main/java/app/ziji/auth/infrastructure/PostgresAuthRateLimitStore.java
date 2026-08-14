package app.ziji.auth.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.AuthRateLimitSubjects;
import app.ziji.auth.application.RateLimitDecision;
import app.ziji.auth.domain.AuthRateLimitWindow;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.RateLimitDimension;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL 固定窗口实现；每次请求按冻结顺序对全部桶做原子 UPSERT，拒绝不抛异常回滚计数。
 */
@Repository
public class PostgresAuthRateLimitStore implements AuthRateLimitStore {

	private static final String ACTION = "SEND_EMAIL_CHALLENGE";
	private static final String POLICY = "AUTH_CHALLENGE_V1";

	private static final String UPSERT_SQL = """
		INSERT INTO auth_rate_limit_buckets AS bucket
			(id, action, purpose, dimension, subject_hash, hash_key_version,
			 policy_code, window_code, window_seconds, limit_count,
			 window_started_at, window_ends_at, request_count, blocked_until,
			 created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1, NULL,
			CAST(? AS timestamptz), CAST(? AS timestamptz))
		ON CONFLICT (
			action, purpose, dimension, subject_hash, hash_key_version,
			policy_code, window_code, window_started_at
		) DO UPDATE SET
			request_count = bucket.request_count + 1,
			blocked_until = CASE
				WHEN bucket.request_count + 1 > EXCLUDED.limit_count
				THEN EXCLUDED.window_ends_at
				ELSE NULL
			END,
			updated_at = EXCLUDED.updated_at
		RETURNING request_count, limit_count, window_ends_at
		""";

	private final DSLContext dsl;
	private final HmacSubjectHasher subjectHasher;

	public PostgresAuthRateLimitStore(DSLContext dsl, HmacSubjectHasher subjectHasher) {
		this.dsl = dsl;
		this.subjectHasher = subjectHasher;
	}

	@Override
	public RateLimitDecision consume(
		EmailChallengePurpose purpose,
		AuthRateLimitSubjects subjects,
		Instant now) {
		if (purpose == null || subjects == null || now == null) {
			throw new AuthInfrastructureException("限流请求输入无效。");
		}
		int retryAfterSeconds = 0;
		for (AuthRateLimitWindow window : AuthRateLimitWindow.ordered()) {
			WindowBounds bounds = WindowBounds.forWindow(window, now);
			byte[] subject = subjectBytes(window.dimension(), subjects);
			for (AuthHmacKey key : subjectHasher.keyRing().keysInVersionOrder()) {
				byte[] subjectHash = subjectHasher.digest(
					purpose, window.dimension(), subject, key);
				Record result = upsert(window, purpose, key, subjectHash, bounds, now);
				int requestCount = result.get("request_count", Integer.class);
				int limitCount = result.get("limit_count", Integer.class);
				OffsetDateTime windowEndsAt = result.get("window_ends_at", OffsetDateTime.class);
				if (requestCount > limitCount) {
					Duration remainingDuration = Duration.between(now, windowEndsAt.toInstant());
					long remaining = remainingDuration.getSeconds()
						+ (remainingDuration.getNano() > 0 ? 1 : 0);
					retryAfterSeconds = Math.max(retryAfterSeconds, (int) Math.max(1, remaining));
				}
			}
		}
		return retryAfterSeconds == 0
			? RateLimitDecision.permitted()
			: RateLimitDecision.denied(retryAfterSeconds);
	}

	private Record upsert(
		AuthRateLimitWindow window,
		EmailChallengePurpose purpose,
		AuthHmacKey key,
		byte[] subjectHash,
		WindowBounds bounds,
		Instant now) {
		Record result = dsl.resultQuery(
			UPSERT_SQL,
			uuid(), ACTION, purpose.name(), window.dimension().name(), subjectHash,
			key.version(), POLICY, window.code(), window.seconds(), window.limit(),
			bounds.startedAt(), bounds.endsAt(), utc(now), utc(now))
			.fetchOne();
		if (result == null) {
			throw new AuthInfrastructureException("限流桶更新未返回结果。");
		}
		return result;
	}

	private static byte[] subjectBytes(RateLimitDimension dimension, AuthRateLimitSubjects subjects) {
		return switch (dimension) {
			case IP -> subjects.ipBytes();
			case EMAIL -> subjects.emailBytes();
			case DEVICE -> subjects.deviceBytes();
		};
	}

	private static UUID uuid() {
		// 限流桶 ID 由应用生成，数据库只负责保存事实和唯一约束。
		return UUID.randomUUID();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private record WindowBounds(OffsetDateTime startedAt, OffsetDateTime endsAt) {
		private static WindowBounds forWindow(AuthRateLimitWindow window, Instant now) {
			return new WindowBounds(
				window.windowStartedAt(now).atOffset(ZoneOffset.UTC),
				window.windowEndsAt(now).atOffset(ZoneOffset.UTC));
		}
	}
}
