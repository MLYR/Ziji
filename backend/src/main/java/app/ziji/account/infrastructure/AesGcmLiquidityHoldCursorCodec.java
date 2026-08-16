package app.ziji.account.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.account.application.LiquidityHoldCursorCodec;
import app.ziji.account.application.LiquidityHoldException;
import app.ziji.account.application.LiquidityHoldKeysetPosition;

/**
 * 专用 AES-GCM 游标：payload 同时保存账户、固定过滤/排序标识、API 主版本和精确 keyset 边界，且不复用认证或幂等密钥。
 */
public final class AesGcmLiquidityHoldCursorCodec implements LiquidityHoldCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int API_MAJOR_VERSION = 1;
	private static final byte FULL_HISTORY_FILTER = 1;
	private static final byte CREATED_AT_DESC_ID_DESC = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int PAYLOAD_BYTES = 1 + Integer.BYTES + 2 + (Long.BYTES * 5) + Integer.BYTES;
	private static final byte[] AAD = "ziji-liquidity-hold-list-cursor-v1".getBytes(StandardCharsets.UTF_8);

	private final SecretKey key;
	private final SecureRandom random;

	public AesGcmLiquidityHoldCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("流动性占用游标密钥无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID accountId, LiquidityHoldKeysetPosition position) {
		if (accountId == null || position == null) {
			throw invalid();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(AAD);
			byte[] encrypted = cipher.doFinal(payload(accountId, position));
			ByteBuffer output = ByteBuffer.allocate(nonce.length + encrypted.length);
			output.put(nonce).put(encrypted);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(output.array());
		} catch (GeneralSecurityException exception) {
			throw invalid();
		}
	}

	@Override
	public LiquidityHoldKeysetPosition decode(UUID accountId, String cursor) {
		if (accountId == null || cursor == null || cursor.isBlank() || cursor.length() > 512) {
			throw invalid();
		}
		try {
			byte[] encoded = Base64.getUrlDecoder().decode(cursor);
			if (encoded.length <= NONCE_BYTES + 16) {
				throw invalid();
			}
			ByteBuffer input = ByteBuffer.wrap(encoded);
			byte[] nonce = new byte[NONCE_BYTES];
			input.get(nonce);
			byte[] encrypted = new byte[input.remaining()];
			input.get(encrypted);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(AAD);
			return position(accountId, cipher.doFinal(encrypted));
		} catch (IllegalArgumentException | GeneralSecurityException exception) {
			throw invalid();
		}
	}

	private static byte[] payload(UUID accountId, LiquidityHoldKeysetPosition position) {
		ByteBuffer output = ByteBuffer.allocate(PAYLOAD_BYTES);
		output.put(FORMAT_VERSION).putInt(API_MAJOR_VERSION).put(FULL_HISTORY_FILTER).put(CREATED_AT_DESC_ID_DESC);
		putUuid(output, accountId);
		output.putLong(position.createdAt().getEpochSecond()).putInt(position.createdAt().getNano());
		putUuid(output, position.holdId());
		return output.array();
	}

	private static LiquidityHoldKeysetPosition position(UUID expectedAccountId, byte[] payload) {
		if (payload.length != PAYLOAD_BYTES) {
			throw invalid();
		}
		ByteBuffer input = ByteBuffer.wrap(payload);
		if (input.get() != FORMAT_VERSION || input.getInt() != API_MAJOR_VERSION || input.get() != FULL_HISTORY_FILTER
			|| input.get() != CREATED_AT_DESC_ID_DESC) {
			throw invalid();
		}
		UUID accountId = uuid(input);
		long seconds = input.getLong();
		int nanos = input.getInt();
		UUID holdId = uuid(input);
		if (!expectedAccountId.equals(accountId) || nanos < 0 || nanos > 999_999_999) {
			throw invalid();
		}
		try {
			return new LiquidityHoldKeysetPosition(Instant.ofEpochSecond(seconds, nanos), holdId);
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private static void putUuid(ByteBuffer output, UUID value) {
		output.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
	}

	private static UUID uuid(ByteBuffer input) {
		return new UUID(input.getLong(), input.getLong());
	}

	private static LiquidityHoldException.Validation invalid() {
		return new LiquidityHoldException.Validation();
	}
}
