package app.ziji.account.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.LiquidityHoldKeysetPosition;
import app.ziji.account.application.LiquidityHoldException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 游标必须是跨重启可验证的专用不透明 keyset 边界，且拒绝篡改/跨账户复用。 */
class AesGcmLiquidityHoldCursorCodecTests {

	@Test
	void roundTripsOpaqueBoundaryAndDoesNotExposeRawPosition() {
		AesGcmLiquidityHoldCursorCodec codec = codec();
		UUID accountId = UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-08-15T03:04:05.123456Z");
		UUID holdId = UUID.randomUUID();
		String cursor = codec.encode(accountId, new LiquidityHoldKeysetPosition(createdAt, holdId));

		assertEquals(new LiquidityHoldKeysetPosition(createdAt, holdId), codec.decode(accountId, cursor));
		assertFalse(cursor.contains(accountId.toString()));
		assertFalse(cursor.contains(createdAt.toString()));
		assertFalse(cursor.getBytes(StandardCharsets.UTF_8).length < 40);
	}

	@Test
	void rejectsTamperingForeignAccountAndMalformedPayload() {
		AesGcmLiquidityHoldCursorCodec codec = codec();
		UUID accountId = UUID.randomUUID();
		String cursor = codec.encode(accountId, new LiquidityHoldKeysetPosition(Instant.now(), UUID.randomUUID()));
		char replacement = cursor.charAt(0) == 'A' ? 'B' : 'A';
		String tampered = replacement + cursor.substring(1);

		assertThrows(LiquidityHoldException.Validation.class, () -> codec.decode(accountId, tampered));
		assertThrows(LiquidityHoldException.Validation.class, () -> codec.decode(UUID.randomUUID(), cursor));
		assertThrows(LiquidityHoldException.Validation.class, () -> codec.decode(accountId, "bad"));
	}

	private static AesGcmLiquidityHoldCursorCodec codec() {
		return new AesGcmLiquidityHoldCursorCodec("liquidity-hold-cursor-key-012345".getBytes(StandardCharsets.UTF_8), new java.security.SecureRandom());
	}
}
