package app.ziji.auth.application;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 验证码 Hash 端口；实现负责版本化格式和常量时间校验。 */
public interface ChallengeCodeHasher {

	String hash(EmailChallengePurpose purpose, String normalizedEmail, String code);

	boolean matches(String storedHash, EmailChallengePurpose purpose, String normalizedEmail, String code);
}
