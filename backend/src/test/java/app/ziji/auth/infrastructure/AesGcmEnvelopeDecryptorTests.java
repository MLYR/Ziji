package app.ziji.auth.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import app.ziji.auth.application.EncryptedCodeEnvelope;
import app.ziji.auth.domain.EmailChallengePurpose;
import org.junit.jupiter.api.Test;

/** 信封解密必须与加密器对称，并拒绝算法、密钥版本和非 6 位明文。 */
class AesGcmEnvelopeDecryptorTests {

	@Test
	void decryptsRegisterAndResetCodesProducedByEncryptor() {
		EnvelopeKey key = new EnvelopeKey(4, key((byte) 4));
		AesGcmEnvelopeEncryptor encryptor = new AesGcmEnvelopeEncryptor(key, new SecureRandom());
		AesGcmEnvelopeDecryptor decryptor = new AesGcmEnvelopeDecryptor(key);
		UUID challengeId = UUID.randomUUID();

		assertEquals("123456", decryptor.decrypt(
			challengeId, EmailChallengePurpose.REGISTER,
			encryptor.encrypt(challengeId, EmailChallengePurpose.REGISTER, "123456")));
		assertEquals("654321", decryptor.decrypt(
			challengeId, EmailChallengePurpose.RESET_PASSWORD,
			encryptor.encrypt(challengeId, EmailChallengePurpose.RESET_PASSWORD, "654321")));
	}

	@Test
	void rejectsUnsupportedAlgorithm() {
		EnvelopeKey key = new EnvelopeKey(4, key((byte) 4));
		AesGcmEnvelopeEncryptor encryptor = new AesGcmEnvelopeEncryptor(key, new SecureRandom());
		UUID challengeId = UUID.randomUUID();
		EncryptedCodeEnvelope valid = encryptor.encrypt(
			challengeId, EmailChallengePurpose.REGISTER, "123456");
		EncryptedCodeEnvelope unsupported = new EncryptedCodeEnvelope(
			"A128GCM", valid.keyEncryptionAlgorithm(), valid.keyVersion(), valid.nonce(),
			valid.ciphertext(), valid.wrappedDataKey(), valid.wrappedDataKeyNonce());

		assertThrows(AuthInfrastructureException.class,
			() -> new AesGcmEnvelopeDecryptor(key).decrypt(
				challengeId, EmailChallengePurpose.REGISTER, unsupported));
	}

	@Test
	void rejectsKeyVersionMismatch() {
		byte[] secret = key((byte) 9);
		EnvelopeKey current = new EnvelopeKey(1, secret);
		EnvelopeKey other = new EnvelopeKey(2, secret);
		UUID challengeId = UUID.randomUUID();
		EncryptedCodeEnvelope envelope = new AesGcmEnvelopeEncryptor(current, new SecureRandom())
			.encrypt(challengeId, EmailChallengePurpose.REGISTER, "123456");

		assertThrows(AuthInfrastructureException.class,
			() -> new AesGcmEnvelopeDecryptor(other).decrypt(
				challengeId, EmailChallengePurpose.REGISTER, envelope));
	}

	@Test
	void rejectsNonDigitPlaintext() {
		EnvelopeKey key = new EnvelopeKey(4, key((byte) 4));
		UUID challengeId = UUID.randomUUID();
		EncryptedCodeEnvelope envelope = new AesGcmEnvelopeEncryptor(key, new SecureRandom())
			.encrypt(challengeId, EmailChallengePurpose.REGISTER, "abcdef");

		assertThrows(AuthInfrastructureException.class,
			() -> new AesGcmEnvelopeDecryptor(key).decrypt(
				challengeId, EmailChallengePurpose.REGISTER, envelope));
	}

	private static byte[] key(byte value) {
		byte[] key = new byte[32];
		Arrays.fill(key, value);
		return key;
	}
}
