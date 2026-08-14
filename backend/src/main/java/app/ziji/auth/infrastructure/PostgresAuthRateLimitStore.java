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
import app.ziji.auth.domain.LoginRateLimitPurpose;
import app.ziji.auth.domain.LoginRateLimitWindow;
import app.ziji.auth.domain.RateLimitDimension;
import app.ziji.auth.domain.SourceAddress;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL 固定窗口实现；每次请求按冻结顺序对全部桶做原子 UPSERT，拒绝不抛异常回滚计数。
 * 验证码与密码登录使用各自的作用域、窗口集合与 HMAC 域，但共用同一张限流事实表与同一 UPSERT 语义。
 */
@Repository
public class PostgresAuthRateLimitStore implements AuthRateLimitStore {

	private static final String CHALLENGE_ACTION = "SEND_EMAIL_CHALLENGE";
	private static final String CHALLENGE_POLICY = "AUTH_CHALLENGE_V1";
	private static final String LOGIN_ACTION = "LOGIN_PASSWORD";
	private static final String LOGIN_POLICY = "AUTH_LOGIN_V1";

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
			WindowBounds bounds = WindowBounds.forWindow(window.windowStartedAt(now), window.windowEndsAt(now));
			byte[] subject = subjectBytes(window.dimension(), subjects);
			for (AuthHmacKey key : subjectHasher.keyRing().keysInVersionOrder()) {
				byte[] subjectHash = subjectHasher.digest(
					purpose, window.dimension(), subject, key);
				retryAfterSeconds = Math.max(retryAfterSeconds, upsertAndMeasure(
					window.code(), window.seconds(), window.limit(), window.dimension(),
					purpose.name(), CHALLENGE_ACTION, CHALLENGE_POLICY, key, subjectHash, bounds, now));
			}
		}
		return retryAfterSeconds == 0
			? RateLimitDecision.permitted()
			: RateLimitDecision.denied(retryAfterSeconds);
	}

	@Override
	public RateLimitDecision consumeLogin(
		String normalizedEmail,
		SourceAddress sourceAddress,
		Instant now) {
		if (normalizedEmail == null || normalizedEmail.isBlank() || sourceAddress == null || now == null) {
			throw new AuthInfrastructureException("登录限流请求输入无效。");
		}
		// 登录只使用 IP 与 EMAIL；deviceId 传 null，AuthRateLimitSubjects 不会为登录生成 DEVICE 主体。
		AuthRateLimitSubjects subjects = AuthRateLimitSubjects.of(normalizedEmail, null, sourceAddress);
		int retryAfterSeconds = 0;
		for (LoginRateLimitWindow window : LoginRateLimitWindow.ordered()) {
			WindowBounds bounds = WindowBounds.forWindow(window.windowStartedAt(now), window.windowEndsAt(now));
			byte[] subject = subjectBytes(window.dimension(), subjects);
			for (AuthHmacKey key : subjectHasher.keyRing().keysInVersionOrder()) {
				byte[] subjectHash = subjectHasher.digestLogin(
					LoginRateLimitPurpose.LOGIN, window.dimension(), subject, key);
				retryAfterSeconds = Math.max(retryAfterSeconds, upsertAndMeasure(
					window.code(), window.seconds(), window.limit(), window.dimension(),
					LoginRateLimitPurpose.LOGIN.name(), LOGIN_ACTION, LOGIN_POLICY, key, subjectHash, bounds, now));
			}
		}
		return retryAfterSeconds == 0
			? RateLimitDecision.permitted()
			: RateLimitDecision.denied(retryAfterSeconds);
	}

	/**
	 * 对单个桶做原子 UPSERT 并在其超限时累加最长 Retry-After；返回该桶贡献的剩余秒数（未超限为 0）。
	 */
	private int upsertAndMeasure(
		String windowCode,
		int windowSeconds,
		int limitCount,
		RateLimitDimension dimension,
		String purposeName,
		String action,
		String policy,
		AuthHmacKey key,
		byte[] subjectHash,
		WindowBounds bounds,
		Instant now) {
		Record result = dsl.resultQuery(
			UPSERT_SQL,
			uuid(), action, purposeName, dimension.name(), subjectHash,
			key.version(), policy, windowCode, windowSeconds, limitCount,
			bounds.startedAt(), bounds.endsAt(), utc(now), utc(now))
			.fetchOne();
		if (result == null) {
			throw new AuthInfrastructureException("限流桶更新未返回结果。");
		}
		int requestCount = result.get("request_count", Integer.class);
		int storedLimit = result.get("limit_count", Integer.class);
		OffsetDateTime windowEndsAt = result.get("window_ends_at", OffsetDateTime.class);
		if (requestCount > storedLimit) {
			Duration remainingDuration = Duration.between(now, windowEndsAt.toInstant());
			long remaining = remainingDuration.getSeconds()
				+ (remainingDuration.getNano() > 0 ? 1 : 0);
			return (int) Math.max(1, remaining);
		}
		return 0;
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
		private static WindowBounds forWindow(Instant startedAt, Instant endsAt) {
			return new WindowBounds(startedAt.atOffset(ZoneOffset.UTC), endsAt.atOffset(ZoneOffset.UTC));
		}
	}
}
