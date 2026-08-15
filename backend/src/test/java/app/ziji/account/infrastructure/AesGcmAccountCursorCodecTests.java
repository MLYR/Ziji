package app.ziji.account.infrastructure;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import app.ziji.account.application.AccountKeysetPosition;
import app.ziji.account.application.AccountQueryValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** AES-GCM 游标应保持不透明，并绑定当前用户、排序边界与完整性校验。 */
class AesGcmAccountCursorCodecTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
	private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03.456789Z");

	@Test
	void roundTripsOpaqueCursorWithoutExposingRawPosition() {
		AesGcmAccountCursorCodec codec = codec();
		AccountKeysetPosition position = new AccountKeysetPosition(CREATED_AT, ACCOUNT_ID);

		String cursor = codec.encode(USER_ID, position);
		AccountKeysetPosition decoded = codec.decode(USER_ID, cursor);

		assertEquals(position, decoded);
		assertFalse(cursor.contains(ACCOUNT_ID.toString()));
		assertFalse(cursor.contains(Long.toString(CREATED_AT.getEpochSecond())));
		assertFalse(java.util.Arrays.equals(rawPayload(position), Base64.getUrlDecoder().decode(cursor)));
	}

	@Test
	void rejectsTamperedMalformedAndCrossUserCursor() {
		AesGcmAccountCursorCodec codec = codec();
		String cursor = codec.encode(USER_ID, new AccountKeysetPosition(CREATED_AT, ACCOUNT_ID));
		String tampered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A");

		assertThrows(AccountQueryValidationException.class, () -> codec.decode(USER_ID, tampered));
		assertThrows(AccountQueryValidationException.class, () -> codec.decode(USER_ID, "not-a-cursor"));
		assertThrows(AccountQueryValidationException.class, () -> codec.decode(OTHER_USER_ID, cursor));
		assertThrows(AccountQueryValidationException.class, () -> codec.decode(USER_ID, ""));
	}

	@Test
	void rejectsInvalidKeyConfiguration() {
		assertThrows(IllegalArgumentException.class,
			() -> new AesGcmAccountCursorCodec(new byte[31], new SecureRandom()));
		assertThrows(IllegalArgumentException.class,
			() -> new AesGcmAccountCursorCodec(new byte[32], null));
	}

	private static AesGcmAccountCursorCodec codec() {
		byte[] key = "account-cursor-key-0123456789abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		return new AesGcmAccountCursorCodec(key, new SecureRandom());
	}

	private static byte[] rawPayload(AccountKeysetPosition position) {
		return ByteBuffer.allocate(1 + Long.BYTES + Integer.BYTES + Long.BYTES + Long.BYTES)
			.put((byte) 1)
			.putLong(position.createdAt().getEpochSecond())
			.putInt(position.createdAt().getNano())
			.putLong(position.accountId().getMostSignificantBits())
			.putLong(position.accountId().getLeastSignificantBits())
			.array();
	}
}
