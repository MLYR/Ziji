package app.ziji.ledger.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.ledger.application.TransactionCursorCodec;
import app.ziji.ledger.application.TransactionKeysetPosition;
import app.ziji.ledger.application.TransactionQuery;
import app.ziji.ledger.application.TransactionQueryValidationException;

/**
 * AES-GCM 保护交易 keyset 游标；AAD 绑定当前用户，载荷绑定筛选、排序、API 主版本和 operationId。
 */
public final class AesGcmTransactionCursorCodec implements TransactionCursorCodec {

	private static final byte FORMAT_VERSION = 1;
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int HASH_BYTES = 32;
	private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Long.BYTES * 2 + HASH_BYTES;
	private static final String DOMAIN = "ziji-api-v1-listTransactions-businessDate-desc-transactionId-desc";

	private final SecretKeySpec key;
	private final SecureRandom random;

	public AesGcmTransactionCursorCodec(byte[] keyBytes, SecureRandom random) {
		if (keyBytes == null || keyBytes.length != KEY_BYTES || random == null) {
			throw new IllegalArgumentException("交易游标密钥配置无效。");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
		this.random = random;
	}

	@Override
	public String encode(UUID userId, TransactionQuery query, TransactionKeysetPosition position) {
		if (userId == null || query == null || position == null) {
			throw new TransactionQueryValidationException();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData(userId)).doFinal(payload(query, position));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
				ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("交易游标加密失败。", exception);
		}
	}

	@Override
	public TransactionKeysetPosition decode(UUID userId, TransactionQuery query, String cursor) {
		if (userId == null || query == null || cursor == null || cursor.isBlank() || cursor.length() > 500) {
			throw new TransactionQueryValidationException();
		}
		byte[] encoded;
		try {
			encoded = Base64.getUrlDecoder().decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new TransactionQueryValidationException();
		}
		// Base64 最后一个字符的未使用位也必须规范化，避免等价编码绕过“篡改即拒绝”。
		if (!Base64.getUrlEncoder().withoutPadding().encodeToString(encoded).equals(cursor)) {
			throw new TransactionQueryValidationException();
		}
		if (encoded.length != NONCE_BYTES + PAYLOAD_BYTES + TAG_BITS / Byte.SIZE) {
			throw new TransactionQueryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		try {
			return readPayload(cipher(Cipher.DECRYPT_MODE, nonce, associatedData(userId)).doFinal(encrypted), query);
		} catch (GeneralSecurityException | RuntimeException exception) {
			throw new TransactionQueryValidationException();
		}
	}

	private Cipher cipher(int mode, byte[] nonce, byte[] aad) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(aad);
		return cipher;
	}

	private static byte[] payload(TransactionQuery query, TransactionKeysetPosition position) {
		byte[] signature = signature(query);
		return ByteBuffer.allocate(PAYLOAD_BYTES)
			.put(FORMAT_VERSION)
			.putLong(position.businessDate().toEpochDay())
			.putLong(position.transactionId().getMostSignificantBits())
			.putLong(position.transactionId().getLeastSignificantBits())
			.put(signature)
			.array();
	}

	private static TransactionKeysetPosition readPayload(byte[] payload, TransactionQuery query) {
		if (payload.length != PAYLOAD_BYTES) {
			throw new TransactionQueryValidationException();
		}
		ByteBuffer input = ByteBuffer.wrap(payload);
		if (input.get() != FORMAT_VERSION) {
			throw new TransactionQueryValidationException();
		}
		long epochDay = input.getLong();
		UUID transactionId = new UUID(input.getLong(), input.getLong());
		byte[] actualSignature = new byte[HASH_BYTES];
		input.get(actualSignature);
		if (!MessageDigest.isEqual(actualSignature, signature(query))) {
			throw new TransactionQueryValidationException();
		}
		try {
			return new TransactionKeysetPosition(LocalDate.ofEpochDay(epochDay), transactionId);
		} catch (RuntimeException exception) {
			throw new TransactionQueryValidationException();
		}
	}

	private static byte[] signature(TransactionQuery query) {
		String canonical = DOMAIN + '|'
			+ value(query.accountId()) + '|'
			+ value(query.type()) + '|'
			+ value(query.dateFrom()) + '|'
			+ value(query.dateTo()) + '|'
			+ value(query.categoryId());
		try {
			return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("交易游标摘要算法不可用。", exception);
		}
	}

	private static String value(Object value) {
		return value == null ? "-" : value.toString();
	}

	private static byte[] associatedData(UUID userId) {
		byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(Integer.BYTES + domain.length + Long.BYTES * 2)
			.putInt(domain.length).put(domain)
			.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).array();
	}
}
