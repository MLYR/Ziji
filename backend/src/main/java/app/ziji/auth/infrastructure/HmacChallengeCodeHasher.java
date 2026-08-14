package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.domain.EmailChallengePurpose;

/** 使用版本化 HMAC Hash 保存验证码，并以常量时间比较验证。 */
public final class HmacChallengeCodeHasher implements ChallengeCodeHasher {

	private static final String DOMAIN = "ziji-email-challenge-code-v1";
	private static final String FORMAT_VERSION = "v1";

	private final AuthHmacKeyRing keyRing;

	public HmacChallengeCodeHasher(AuthHmacKeyRing keyRing) {
		this.keyRing = keyRing;
	}

	@Override
	public String hash(EmailChallengePurpose purpose, String normalizedEmail, String code) {
		if (purpose == null || normalizedEmail == null || code == null
			|| !code.matches("[0-9]{6}")) {
			throw new AuthInfrastructureException("验证码 Hash 输入无效。");
		}
		AuthHmacKey key = keyRing.current();
		byte[] digest = digest(key, purpose, normalizedEmail, code);
		return FORMAT_VERSION + ":" + key.version() + ":"
			+ Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	@Override
	public boolean matches(
		String storedHash,
		EmailChallengePurpose purpose,
		String normalizedEmail,
		String code) {
		if (storedHash == null || purpose == null || normalizedEmail == null || code == null
			|| !code.matches("[0-9]{6}")) {
			return false;
		}
		String[] fields = storedHash.split(":", -1);
		if (fields.length != 3 || !FORMAT_VERSION.equals(fields[0])) {
			return false;
		}
		try {
			int version = Integer.parseInt(fields[1]);
			byte[] expected = Base64.getUrlDecoder().decode(fields[2]);
			if (expected.length != 32) {
				return false;
			}
			AuthHmacKey key = keyRing.find(version).orElse(null);
			if (key == null) {
				return false;
			}
			return MessageDigest.isEqual(expected, digest(key, purpose, normalizedEmail, code));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static byte[] digest(
		AuthHmacKey key,
		EmailChallengePurpose purpose,
		String normalizedEmail,
		String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.secretCopy(), "HmacSHA256"));
			return mac.doFinal(HmacInputEncoder.encode(
				DOMAIN,
				purpose.name().getBytes(StandardCharsets.UTF_8),
				normalizedEmail.getBytes(StandardCharsets.UTF_8),
				code.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new AuthInfrastructureException("验证码 Hash 计算失败。", exception);
		}
	}
}
