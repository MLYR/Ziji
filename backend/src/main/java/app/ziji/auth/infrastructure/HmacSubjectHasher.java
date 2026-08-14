package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.RateLimitDimension;

/** 计算用途、维度和主体三重域分离的 HMAC-SHA-256 限流摘要。 */
public final class HmacSubjectHasher {

	private static final String DOMAIN = "ziji-auth-rate-limit-v1";

	private final AuthHmacKeyRing keyRing;

	public HmacSubjectHasher(AuthHmacKeyRing keyRing) {
		this.keyRing = keyRing;
	}

	byte[] digest(
		EmailChallengePurpose purpose,
		RateLimitDimension dimension,
		byte[] normalizedSubject,
		AuthHmacKey key) {
		if (purpose == null || dimension == null || normalizedSubject == null || key == null) {
			throw new AuthInfrastructureException("限流摘要输入无效。");
		}
		byte[] purposeBytes = purpose.name().getBytes(StandardCharsets.UTF_8);
		byte[] dimensionBytes = dimension.name().getBytes(StandardCharsets.UTF_8);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.secretCopy(), "HmacSHA256"));
			return mac.doFinal(HmacInputEncoder.encode(
				DOMAIN, purposeBytes, dimensionBytes, normalizedSubject));
		} catch (GeneralSecurityException exception) {
			throw new AuthInfrastructureException("限流摘要计算失败。", exception);
		}
	}

	AuthHmacKeyRing keyRing() {
		return keyRing;
	}
}
