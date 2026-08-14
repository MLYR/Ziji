package app.ziji.auth.application;

import java.time.Instant;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;

/** 认证限流持久化端口；实现必须在 PostgreSQL 中按冻结顺序原子占用全部桶。 */
public interface AuthRateLimitStore {

	/**
	 * 消费验证码签发限流桶；保留现有验证码限流语义与调用方。
	 * 实现按 IP→邮箱→设备、短窗→长窗的固定顺序对全部桶做原子 UPSERT，拒绝也提交计数。
	 */
	RateLimitDecision consume(
		EmailChallengePurpose purpose,
		AuthRateLimitSubjects subjects,
		Instant now);

	/**
	 * 消费密码登录限流桶；登录只使用来源 IP 和规范化邮箱两个维度，不创建 DEVICE 桶。
	 * 作用域固定为 action {@code LOGIN_PASSWORD}、purpose {@code LOGIN}、policy {@code AUTH_LOGIN_V1}，
	 * 并使用独立的登录 HMAC 域，与验证码限流事实严格隔离；拒绝同样提交计数而非回滚。
	 */
	RateLimitDecision consumeLogin(
		String normalizedEmail,
		SourceAddress sourceAddress,
		Instant now);
}
