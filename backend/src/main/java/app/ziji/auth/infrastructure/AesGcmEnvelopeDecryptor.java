package app.ziji.auth.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.application.EncryptedCodeEnvelope;
import app.ziji.auth.application.EnvelopeDecryptor;
import app.ziji.auth.domain.EmailChallengePurpose;

/** 与 AesGcmEnvelopeEncryptor 对应的事件信封解密；失败只抛出不含密文的安全异常。 */
public final class AesGcmEnvelopeDecryptor implements EnvelopeDecryptor {

	private static final int TAG_BITS = 128;

	private final EnvelopeKey keyEncryptionKey;

	public AesGcmEnvelopeDecryptor(EnvelopeKey keyEncryptionKey) {
		if (keyEncryptionKey == null) {
			throw new AuthInfrastructureException("邮件信封解密密钥不能为空。");
		}
		this.keyEncryptionKey = keyEncryptionKey;
	}

	@Override
	public String decrypt(UUID challengeId, EmailChallengePurpose purpose, EncryptedCodeEnvelope envelope) {
		if (challengeId == null || purpose == null || envelope == null) {
			throw new AuthInfrastructureException("邮件信封输入无效。");
		}
		if (!"A256GCM".equals(envelope.algorithm()) || !"A256GCM".equals(envelope.keyEncryptionAlgorithm())) {
			throw new AuthInfrastructureException("邮件信封算法不受支持。");
		}
		// 当前只装配一把 KEK；版本不一致既不能解密，也没有可回退的上一把密钥。
		if (envelope.keyVersion() != keyEncryptionKey.version()) {
			throw new AuthInfrastructureException("邮件信封密钥版本不匹配。");
		}
		try {
			byte[] wrappedKeyNonce = decode(envelope.wrappedDataKeyNonce());
			Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
			unwrap.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyEncryptionKey.secretCopy(), "AES"),
				new GCMParameterSpec(TAG_BITS, wrappedKeyNonce));
			unwrap.updateAAD(HmacInputEncoder.encode(
				"ziji-email-challenge-kek-v1",
				ByteBuffer.allocate(Integer.BYTES).putInt(keyEncryptionKey.version()).array()));
			byte[] dataKey = unwrap.doFinal(decode(envelope.wrappedDataKey()));

			Cipher payload = Cipher.getInstance("AES/GCM/NoPadding");
			payload.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
				new GCMParameterSpec(TAG_BITS, decode(envelope.nonce())));
			payload.updateAAD(HmacInputEncoder.encode(
				"ziji-email-challenge-envelope-v1", asBytes(challengeId),
				purpose.name().getBytes(StandardCharsets.UTF_8)));
			String plaintext = new String(payload.doFinal(decode(envelope.ciphertext())), StandardCharsets.UTF_8);
			if (!plaintext.matches("[0-9]{6}")) {
				throw new AuthInfrastructureException("邮件信封明文无效。");
			}
			return plaintext;
		} catch (GeneralSecurityException | IllegalArgumentException exception) {
			throw new AuthInfrastructureException("邮件信封解密失败。", exception);
		}
	}

	private static byte[] asBytes(UUID value) {
		return ByteBuffer.allocate(Long.BYTES * 2)
			.putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits())
			.array();
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}
}
