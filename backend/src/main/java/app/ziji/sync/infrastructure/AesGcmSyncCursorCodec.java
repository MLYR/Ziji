package app.ziji.sync.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.sync.application.SyncCursorCodec;
import app.ziji.sync.application.SyncQueryValidationException;

/** AES-GCM 隐藏同步 sequence，并通过独立游标域和当前用户 AAD 拒绝跨用途、跨用户复用。 */
public final class AesGcmSyncCursorCodec implements SyncCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int PAYLOAD_BYTES = 1 + Long.BYTES;
	private static final String DOMAIN = "ziji-sync-change-cursor-v1";

	private final SecretKeySpec key;
	private final SecureRandom random;

	public AesGcmSyncCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("同步游标密钥配置无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID userId, long sequence) {
		if (userId == null || sequence < 1) {
			throw new SyncQueryValidationException();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData(userId));
			byte[] encrypted = cipher.doFinal(ByteBuffer.allocate(PAYLOAD_BYTES)
				.put(FORMAT_VERSION)
				.putLong(sequence)
				.array());
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
				ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("同步游标加密失败。", exception);
		}
	}

	@Override
	public long decode(UUID userId, String cursor) {
		if (userId == null || cursor == null || cursor.isBlank() || cursor.length() > 500) {
			throw new SyncQueryValidationException();
		}
		byte[] encoded;
		try {
			encoded = Base64.getUrlDecoder().decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new SyncQueryValidationException();
		}
		if (encoded.length != NONCE_BYTES + PAYLOAD_BYTES + TAG_BITS / Byte.SIZE) {
			throw new SyncQueryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		try {
			byte[] payload = cipher(Cipher.DECRYPT_MODE, nonce, associatedData(userId)).doFinal(encrypted);
			ByteBuffer plaintext = ByteBuffer.wrap(payload);
			if (payload.length != PAYLOAD_BYTES || plaintext.get() != FORMAT_VERSION) {
				throw new SyncQueryValidationException();
			}
			long sequence = plaintext.getLong();
			if (sequence < 1) {
				throw new SyncQueryValidationException();
			}
			return sequence;
		} catch (GeneralSecurityException exception) {
			throw new SyncQueryValidationException();
		}
	}

	private Cipher cipher(int mode, byte[] nonce, byte[] associatedData) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	private static byte[] associatedData(UUID userId) {
		byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(Integer.BYTES + domain.length + Long.BYTES * 2)
			.putInt(domain.length)
			.put(domain)
			.putLong(userId.getMostSignificantBits())
			.putLong(userId.getLeastSignificantBits())
			.array();
	}
}
