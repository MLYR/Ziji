package app.ziji.category.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.category.application.CategoryCursorCodec;
import app.ziji.category.application.CategoryKeysetPosition;
import app.ziji.category.application.CategoryValidationException;

/**
 * AES-GCM 分类 keyset 游标；当前用户与列表过滤条件进入 AAD，跨用户或换条件复用都会校验失败。
 */
public final class AesGcmCategoryCursorCodec implements CategoryCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Integer.BYTES + Long.BYTES * 2;
	private static final String DOMAIN = "ziji-category-list-cursor-v1";

	private final SecretKeySpec key;
	private final SecureRandom random;

	public AesGcmCategoryCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("分类游标密钥配置无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID userId, UUID accountIdFilter, CategoryKeysetPosition position) {
		if (userId == null || position == null) {
			throw new CategoryValidationException();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData(userId, accountIdFilter));
			byte[] encrypted = cipher.doFinal(payload(position));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
				ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("分类游标加密失败。", exception);
		}
	}

	@Override
	public CategoryKeysetPosition decode(UUID userId, UUID accountIdFilter, String cursor) {
		if (userId == null || cursor == null || cursor.isBlank() || cursor.length() > 500) {
			throw new CategoryValidationException();
		}
		byte[] encoded;
		try {
			encoded = Base64.getUrlDecoder().decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new CategoryValidationException();
		}
		if (encoded.length != NONCE_BYTES + PAYLOAD_BYTES + TAG_BITS / Byte.SIZE) {
			throw new CategoryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		try {
			return position(cipher(Cipher.DECRYPT_MODE, nonce, associatedData(userId, accountIdFilter))
				.doFinal(encrypted));
		} catch (GeneralSecurityException exception) {
			throw new CategoryValidationException();
		}
	}

	private Cipher cipher(int mode, byte[] nonce, byte[] associatedData) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	private static byte[] payload(CategoryKeysetPosition position) {
		return ByteBuffer.allocate(PAYLOAD_BYTES)
			.put(FORMAT_VERSION)
			.putLong(position.createdAt().getEpochSecond())
			.putInt(position.createdAt().getNano())
			.putLong(position.categoryId().getMostSignificantBits())
			.putLong(position.categoryId().getLeastSignificantBits())
			.array();
	}

	private static CategoryKeysetPosition position(byte[] payload) {
		if (payload.length != PAYLOAD_BYTES) {
			throw new CategoryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(payload);
		if (input.get() != FORMAT_VERSION) {
			throw new CategoryValidationException();
		}
		long epochSecond = input.getLong();
		int nano = input.getInt();
		if (nano < 0 || nano > 999_999_999) {
			throw new CategoryValidationException();
		}
		return new CategoryKeysetPosition(
			Instant.ofEpochSecond(epochSecond, nano), new UUID(input.getLong(), input.getLong()));
	}

	private static byte[] associatedData(UUID userId, UUID accountIdFilter) {
		byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
		boolean filtered = accountIdFilter != null;
		UUID accountId = Objects.requireNonNullElse(accountIdFilter, new UUID(0L, 0L));
		return ByteBuffer.allocate(Integer.BYTES + domain.length + 1 + Long.BYTES * 4)
			.putInt(domain.length).put(domain)
			.put(filtered ? (byte) 1 : (byte) 0)
			.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits())
			.putLong(accountId.getMostSignificantBits()).putLong(accountId.getLeastSignificantBits())
			.array();
	}
}
