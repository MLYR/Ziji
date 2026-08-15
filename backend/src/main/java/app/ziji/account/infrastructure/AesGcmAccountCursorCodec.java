package app.ziji.account.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.account.application.AccountCursorCodec;
import app.ziji.account.application.AccountKeysetPosition;
import app.ziji.account.application.AccountQueryValidationException;

/**
 * AES-GCM 加密账户列表 keyset 边界，并把当前用户放入 AAD。
 * 令牌不暴露创建时间、账户 ID、排序/SQL 细节，跨用户复用和任意篡改均会校验失败。
 */
public final class AesGcmAccountCursorCodec implements AccountCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Integer.BYTES + Long.BYTES + Long.BYTES;
	private static final String DOMAIN = "ziji-account-list-cursor-v1";

	private final SecretKeySpec key;
	private final SecureRandom random;

	public AesGcmAccountCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("账户游标密钥配置无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID userId, AccountKeysetPosition position) {
		if (userId == null || position == null) {
			throw new AccountQueryValidationException();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData(userId));
			byte[] encrypted = cipher.doFinal(payload(position));
			ByteBuffer output = ByteBuffer.allocate(NONCE_BYTES + encrypted.length);
			output.put(nonce).put(encrypted);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(output.array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("账户游标加密失败。", exception);
		}
	}

	@Override
	public AccountKeysetPosition decode(UUID userId, String cursor) {
		if (userId == null || cursor == null || cursor.isBlank() || cursor.length() > 500) {
			throw new AccountQueryValidationException();
		}
		byte[] encoded;
		try {
			encoded = Base64.getUrlDecoder().decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new AccountQueryValidationException();
		}
		if (encoded.length != NONCE_BYTES + PAYLOAD_BYTES + TAG_BITS / Byte.SIZE) {
			throw new AccountQueryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		try {
			byte[] payload = cipher(Cipher.DECRYPT_MODE, nonce, associatedData(userId)).doFinal(encrypted);
			return position(payload);
		} catch (GeneralSecurityException | DateTimeException exception) {
			throw new AccountQueryValidationException();
		}
	}

	private Cipher cipher(int mode, byte[] nonce, byte[] associatedData) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	private static byte[] payload(AccountKeysetPosition position) {
		Instant createdAt = position.createdAt();
		UUID accountId = position.accountId();
		return ByteBuffer.allocate(PAYLOAD_BYTES)
			.put(FORMAT_VERSION)
			.putLong(createdAt.getEpochSecond())
			.putInt(createdAt.getNano())
			.putLong(accountId.getMostSignificantBits())
			.putLong(accountId.getLeastSignificantBits())
			.array();
	}

	private static AccountKeysetPosition position(byte[] payload) {
		if (payload.length != PAYLOAD_BYTES) {
			throw new AccountQueryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(payload);
		if (input.get() != FORMAT_VERSION) {
			throw new AccountQueryValidationException();
		}
		long epochSecond = input.getLong();
		int nano = input.getInt();
		if (nano < 0 || nano > 999_999_999) {
			throw new AccountQueryValidationException();
		}
		return new AccountKeysetPosition(
			Instant.ofEpochSecond(epochSecond, nano),
			new UUID(input.getLong(), input.getLong()));
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
