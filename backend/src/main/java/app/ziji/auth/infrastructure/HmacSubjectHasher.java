package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.LoginRateLimitPurpose;
import app.ziji.auth.domain.RateLimitDimension;

/**
 * 计算用途、维度和主体三重域分离的 HMAC-SHA-256 限流摘要。
 * 验证码与密码登录使用各自独立的 HMAC 域，确保即便共享密钥也不会跨操作复用同一摘要空间。
 */
public final class HmacSubjectHasher {

	private static final String CHALLENGE_DOMAIN = "ziji-auth-rate-limit-v1";
	private static final String LOGIN_DOMAIN = "ziji-auth-login-rate-limit-v1";

	private final AuthHmacKeyRing keyRing;

	public HmacSubjectHasher(AuthHmacKeyRing keyRing) {
		this.keyRing = keyRing;
	}

	/** 验证码限流摘要；使用验证码专用 HMAC 域。 */
	byte[] digest(
		EmailChallengePurpose purpose,
		RateLimitDimension dimension,
		byte[] normalizedSubject,
		AuthHmacKey key) {
		if (purpose == null || dimension == null || normalizedSubject == null || key == null) {
			throw new AuthInfrastructureException("限流摘要输入无效。");
		}
		return compute(CHALLENGE_DOMAIN, purpose.name(), dimension, normalizedSubject, key);
	}

	/** 密码登录限流摘要；使用登录专用 HMAC 域，不得与验证码域复用。 */
	byte[] digestLogin(
		LoginRateLimitPurpose purpose,
		RateLimitDimension dimension,
		byte[] normalizedSubject,
		AuthHmacKey key) {
		if (purpose == null || dimension == null || normalizedSubject == null || key == null) {
			throw new AuthInfrastructureException("登录限流摘要输入无效。");
		}
		return compute(LOGIN_DOMAIN, purpose.name(), dimension, normalizedSubject, key);
	}

	private static byte[] compute(
		String domain,
		String purposeName,
		RateLimitDimension dimension,
		byte[] normalizedSubject,
		AuthHmacKey key) {
		byte[] purposeBytes = purposeName.getBytes(StandardCharsets.UTF_8);
		byte[] dimensionBytes = dimension.name().getBytes(StandardCharsets.UTF_8);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.secretCopy(), "HmacSHA256"));
			return mac.doFinal(HmacInputEncoder.encode(
				domain, purposeBytes, dimensionBytes, normalizedSubject));
		} catch (GeneralSecurityException exception) {
			throw new AuthInfrastructureException("限流摘要计算失败。", exception);
		}
	}

	AuthHmacKeyRing keyRing() {
		return keyRing;
	}
}
