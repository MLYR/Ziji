package app.ziji.auth.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.application.EncryptedCodeEnvelope;
import app.ziji.auth.application.EnvelopeEncryptor;
import app.ziji.auth.domain.EmailChallengePurpose;

/**
 * 每个事件生成随机数据密钥，以 AES-256-GCM 加密验证码，再用外部 AES-256 KEK 包装数据密钥。
 */
public final class AesGcmEnvelopeEncryptor implements EnvelopeEncryptor {

	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final String AES_GCM = "A256GCM";

	private final EnvelopeKey keyEncryptionKey;
	private final SecureRandom random;

	public AesGcmEnvelopeEncryptor(EnvelopeKey keyEncryptionKey, SecureRandom random) {
		this.keyEncryptionKey = keyEncryptionKey;
		this.random = random;
	}

	@Override
	public EncryptedCodeEnvelope encrypt(UUID challengeId, EmailChallengePurpose purpose, String code) {
		if (challengeId == null || purpose == null || code == null) {
			throw new AuthInfrastructureException("outbox 信封输入无效。");
		}
		try {
			byte[] dataKeyBytes = new byte[KEY_BYTES];
			random.nextBytes(dataKeyBytes);
			byte[] payloadNonce = randomNonce();
			byte[] wrappedKeyNonce = randomNonce();
			byte[] associatedData = associatedData(challengeId, purpose);

			Cipher payloadCipher = cipher(Cipher.ENCRYPT_MODE,
				new SecretKeySpec(dataKeyBytes, "AES"), payloadNonce, associatedData);
			byte[] ciphertext = payloadCipher.doFinal(code.getBytes(StandardCharsets.UTF_8));

			Cipher wrappingCipher = cipher(Cipher.ENCRYPT_MODE,
				new SecretKeySpec(keyEncryptionKey.secretCopy(), "AES"), wrappedKeyNonce,
				keyVersionData(keyEncryptionKey.version()));
			byte[] wrappedDataKey = wrappingCipher.doFinal(dataKeyBytes);
			return new EncryptedCodeEnvelope(
				AES_GCM,
				AES_GCM,
				keyEncryptionKey.version(),
				encode(payloadNonce),
				encode(ciphertext),
				encode(wrappedDataKey),
				encode(wrappedKeyNonce));
		} catch (GeneralSecurityException exception) {
			throw new AuthInfrastructureException("outbox 信封加密失败。", exception);
		}
	}

	private static Cipher cipher(
		int mode,
		SecretKeySpec key,
		byte[] nonce,
		byte[] associatedData) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	private byte[] randomNonce() {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		return nonce;
	}

	private static byte[] associatedData(UUID challengeId, EmailChallengePurpose purpose) {
		return HmacInputEncoder.encode(
			"ziji-email-challenge-envelope-v1",
			asBytes(challengeId), purpose.name().getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] keyVersionData(int version) {
		return HmacInputEncoder.encode(
			"ziji-email-challenge-kek-v1",
			ByteBuffer.allocate(Integer.BYTES).putInt(version).array());
	}

	private static byte[] asBytes(UUID value) {
		return ByteBuffer.allocate(Long.BYTES * 2)
			.putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits())
			.array();
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
