package app.ziji.sync.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

import app.ziji.sync.application.SyncQueryValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 同步游标必须隐藏 sequence，并绑定当前用户、用途和完整性。 */
class AesGcmSyncCursorCodecTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");

	@Test
	void roundTripsOpaqueSequence() {
		AesGcmSyncCursorCodec codec = codec();
		String cursor = codec.encode(USER_ID, 123L);

		assertEquals(123L, codec.decode(USER_ID, cursor));
		assertFalse(cursor.contains("123"));
		assertFalse(cursor.contains(USER_ID.toString()));
	}

	@Test
	void rejectsTamperingMalformedAndCrossUserCursor() {
		AesGcmSyncCursorCodec codec = codec();
		String cursor = codec.encode(USER_ID, 1L);
		String tampered = (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1);

		assertThrows(SyncQueryValidationException.class, () -> codec.decode(USER_ID, tampered));
		assertThrows(SyncQueryValidationException.class, () -> codec.decode(USER_ID, "not-a-cursor"));
		assertThrows(SyncQueryValidationException.class, () -> codec.decode(USER_ID, ""));
		assertThrows(SyncQueryValidationException.class, () -> codec.decode(OTHER_USER_ID, cursor));
		assertThrows(SyncQueryValidationException.class, () -> codec.encode(USER_ID, 0));
	}

	private static AesGcmSyncCursorCodec codec() {
		return new AesGcmSyncCursorCodec(
			"sync-cursor-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8), new SecureRandom());
	}
}
