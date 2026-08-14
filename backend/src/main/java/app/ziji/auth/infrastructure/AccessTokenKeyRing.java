package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 当前签发私钥与当前/上一受信公钥；所有密钥解析均在启动时完成并严格失败关闭。 */
final class AccessTokenKeyRing {

	static final Duration MINIMUM_PREVIOUS_RETENTION = Duration.ofHours(24);

	private final SigningKey current;
	private final Map<String, PublicKey> verificationKeys;

	private AccessTokenKeyRing(SigningKey current, PublicKey previousPublicKey, String previousKid) {
		this.current = current;
		Map<String, PublicKey> keys = new LinkedHashMap<>();
		keys.put(current.kid(), current.publicKey());
		if (previousPublicKey != null) {
			keys.put(previousKid, previousPublicKey);
		}
		this.verificationKeys = Map.copyOf(keys);
	}

	static AccessTokenKeyRing from(AuthSecurityProperties.AccessTokenProperties properties) {
		if (properties == null) {
			throw new AuthInfrastructureException("Access Token 密钥配置缺失。");
		}
		String currentKid = requireKid(properties.getCurrentKid(), "当前 Access Token kid");
		PrivateKey currentPrivateKey = privateKey(properties.getCurrentPrivateKeyPkcs8Base64());
		PublicKey currentPublicKey = publicKey(properties.getCurrentPublicKeyX509Base64());
		validateRsa(currentPrivateKey);
		validateRsa(currentPublicKey);
		validatePair(currentPrivateKey, currentPublicKey);

		String previousKid = properties.getPreviousKid();
		String previousPublicKeyBase64 = properties.getPreviousPublicKeyX509Base64();
		boolean previousKidConfigured = configured(previousKid);
		boolean previousKeyConfigured = configured(previousPublicKeyBase64);
		if (previousKidConfigured != previousKeyConfigured) {
			throw new AuthInfrastructureException("上一 Access Token kid 与公钥必须同时配置。");
		}
		PublicKey previousPublicKey = null;
		if (previousKidConfigured) {
			previousKid = requireKid(previousKid, "上一 Access Token kid");
			if (currentKid.equals(previousKid)) {
				throw new AuthInfrastructureException("Access Token kid 必须唯一。");
			}
			if (properties.getPreviousPublicKeyRetention() == null
				|| properties.getPreviousPublicKeyRetention().compareTo(MINIMUM_PREVIOUS_RETENTION) < 0) {
				throw new AuthInfrastructureException("上一 Access Token 公钥保留时间不足。");
			}
			previousPublicKey = publicKey(previousPublicKeyBase64);
			validateRsa(previousPublicKey);
		}
		return new AccessTokenKeyRing(new SigningKey(currentKid, currentPrivateKey, currentPublicKey),
			previousPublicKey, previousKid);
	}

	SigningKey current() {
		return current;
	}

	Optional<PublicKey> verificationKey(String kid) {
		return Optional.ofNullable(verificationKeys.get(kid));
	}

	private static String requireKid(String value, String name) {
		if (!configured(value) || !value.matches("[A-Za-z0-9._-]{1,100}")) {
			throw new AuthInfrastructureException(name + "配置无效。");
		}
		return value;
	}

	private static PrivateKey privateKey(String value) {
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(
				new PKCS8EncodedKeySpec(decode(value, "当前 Access Token 私钥")));
		} catch (Exception exception) {
			throw new AuthInfrastructureException("当前 Access Token 私钥配置无效。", exception);
		}
	}

	private static PublicKey publicKey(String value) {
		try {
			return KeyFactory.getInstance("RSA").generatePublic(
				new X509EncodedKeySpec(decode(value, "Access Token 公钥")));
		} catch (Exception exception) {
			throw new AuthInfrastructureException("Access Token 公钥配置无效。", exception);
		}
	}

	private static byte[] decode(String value, String name) {
		if (!configured(value)) {
			throw new AuthInfrastructureException(name + "未配置。");
		}
		try {
			return Base64.getDecoder().decode(value);
		} catch (IllegalArgumentException exception) {
			throw new AuthInfrastructureException(name + "配置无效。", exception);
		}
	}

	private static void validateRsa(Object key) {
		int bits = switch (key) {
			case RSAPublicKey publicKey -> publicKey.getModulus().bitLength();
			case RSAPrivateKey privateKey -> privateKey.getModulus().bitLength();
			default -> 0;
		};
		if (bits < 2048) {
			throw new AuthInfrastructureException("Access Token RSA 密钥长度不足。");
		}
	}

	private static void validatePair(PrivateKey privateKey, PublicKey publicKey) {
		try {
			byte[] challenge = "ziji-access-token-key-pair-check".getBytes(StandardCharsets.UTF_8);
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update(challenge);
			byte[] signed = signature.sign();
			signature.initVerify(publicKey);
			signature.update(challenge);
			if (!signature.verify(signed)) {
				throw new AuthInfrastructureException("Access Token 私钥和公钥不匹配。");
			}
		} catch (AuthInfrastructureException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new AuthInfrastructureException("Access Token 私钥和公钥不匹配。", exception);
		}
	}

	private static boolean configured(String value) {
		return value != null && !value.isBlank();
	}

	static final class SigningKey {
		private final String kid;
		private final PrivateKey privateKey;
		private final PublicKey publicKey;

		private SigningKey(String kid, PrivateKey privateKey, PublicKey publicKey) {
			this.kid = kid;
			this.privateKey = privateKey;
			this.publicKey = publicKey;
		}

		String kid() {
			return kid;
		}

		PrivateKey privateKey() {
			return privateKey;
		}

		PublicKey publicKey() {
			return publicKey;
		}
	}
}
