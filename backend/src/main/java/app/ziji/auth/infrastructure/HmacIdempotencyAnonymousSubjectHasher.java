package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.IdempotencyValidationException;

/** 对规范化邮箱计算公开幂等专用、长度前缀域分离的当前/上一 HMAC 摘要。 */
public final class HmacIdempotencyAnonymousSubjectHasher implements IdempotencyAnonymousSubjectHasher {

	private static final String DOMAIN = "ZIJI-IDEMPOTENCY-ANONYMOUS-EMAIL-V1";

	private final IdempotencyHmacKeyRing keyRing;

	public HmacIdempotencyAnonymousSubjectHasher(IdempotencyHmacKeyRing keyRing) {
		if (keyRing == null) {
			throw new AuthInfrastructureException("幂等 HMAC 密钥环不能为空。");
		}
		this.keyRing = keyRing;
	}

	@Override
	public IdempotencySubject.Anonymous forEmail(String email) {
		try {
			byte[] normalized = EmailAddress.normalize(email).value().getBytes(StandardCharsets.UTF_8);
			AuthHmacKey current = keyRing.current();
			IdempotencySubject.AnonymousDigest currentDigest = new IdempotencySubject.AnonymousDigest(
				current.version(), digest(current, normalized));
			AuthHmacKey previous = keyRing.previous();
			IdempotencySubject.AnonymousDigest previousDigest = previous == null ? null
				: new IdempotencySubject.AnonymousDigest(previous.version(), digest(previous, normalized));
			return IdempotencySubject.anonymous(currentDigest, previousDigest);
		} catch (AuthDomainException exception) {
			// 邮箱校验失败发生在持久化前，异常绝不回显匿名主体输入。
			throw new IdempotencyValidationException("匿名幂等主体无效。");
		}
	}

	private static byte[] digest(AuthHmacKey key, byte[] normalizedEmail) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.secretCopy(), "HmacSHA256"));
			return mac.doFinal(HmacInputEncoder.encode(DOMAIN, normalizedEmail));
		} catch (GeneralSecurityException exception) {
			throw new AuthInfrastructureException("匿名幂等主体摘要计算失败。", exception);
		}
	}
}
