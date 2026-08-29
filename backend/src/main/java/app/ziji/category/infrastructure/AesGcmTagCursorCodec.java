package app.ziji.category.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.category.application.TagCursorCodec;
import app.ziji.category.application.TagKeysetPosition;
import app.ziji.category.application.TagValidationException;

/**
 * AES-GCM 标签 keyset 游标；当前用户进入 AAD，跨用户复用会直接校验失败。
 */
public final class AesGcmTagCursorCodec implements TagCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Integer.BYTES + Long.BYTES * 2;
	private static final String DOMAIN = "ziji-tag-list-cursor-v1";

	private final SecretKeySpec key;
	private final SecureRandom random;

	public AesGcmTagCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("标签游标密钥配置无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID userId, TagKeysetPosition position) {
		if (userId == null || position == null) {
			throw new TagValidationException();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData(userId));
			byte[] encrypted = cipher.doFinal(payload(position));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
				ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("标签游标加密失败。", exception);
		}
	}

	@Override
	public TagKeysetPosition decode(UUID userId, String cursor) {
		if (userId == null || cursor == null || cursor.isBlank() || cursor.length() > 500) {
			throw new TagValidationException();
		}
		byte[] encoded;
		try {
			encoded = Base64.getUrlDecoder().decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new TagValidationException();
		}
		if (encoded.length != NONCE_BYTES + PAYLOAD_BYTES + TAG_BITS / Byte.SIZE) {
			throw new TagValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		try {
			return position(cipher(Cipher.DECRYPT_MODE, nonce, associatedData(userId)).doFinal(encrypted));
		} catch (GeneralSecurityException exception) {
			throw new TagValidationException();
		}
	}

	private Cipher cipher(int mode, byte[] nonce, byte[] associatedData) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	private static byte[] payload(TagKeysetPosition position) {
		return ByteBuffer.allocate(PAYLOAD_BYTES)
			.put(FORMAT_VERSION)
			.putLong(position.createdAt().getEpochSecond())
			.putInt(position.createdAt().getNano())
			.putLong(position.tagId().getMostSignificantBits())
			.putLong(position.tagId().getLeastSignificantBits())
			.array();
	}

	private static TagKeysetPosition position(byte[] payload) {
		if (payload.length != PAYLOAD_BYTES) {
			throw new TagValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(payload);
		if (input.get() != FORMAT_VERSION) {
			throw new TagValidationException();
		}
		long epochSecond = input.getLong();
		int nano = input.getInt();
		if (nano < 0 || nano > 999_999_999) {
			throw new TagValidationException();
		}
		return new TagKeysetPosition(
			Instant.ofEpochSecond(epochSecond, nano), new UUID(input.getLong(), input.getLong()));
	}

	private static byte[] associatedData(UUID userId) {
		byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(Integer.BYTES + domain.length + Long.BYTES * 2)
			.putInt(domain.length).put(domain)
			.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits())
			.array();
	}
}
