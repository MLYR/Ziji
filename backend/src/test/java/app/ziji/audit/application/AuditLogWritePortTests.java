package app.ziji.audit.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 审计公共输入只允许追加最小、结构化且受长度限制的脱敏 metadata。 */
class AuditLogWritePortTests {

	@Test
	void acceptsAUserAppendFactWithMinimalMetadata() {
		AuditLogWritePort.AuditLogEntry entry = entry(Map.of(
			"holdId", UUID.randomUUID().toString(),
			"version", "2"));

		assertEquals("2", entry.metadata().get("version"));
		assertEquals(AuditLogWritePort.ActorType.USER, entry.actorType());
	}

	@Test
	void rejectsUnsafeOrIncompleteAppendFacts() {
		assertThrows(IllegalArgumentException.class, () -> entry(null));
		assertThrows(IllegalArgumentException.class, () -> new AuditLogWritePort.AuditLogEntry(
			Instant.now(), null, AuditLogWritePort.ActorType.USER, "ACTION", "HOLD", UUID.randomUUID(),
			UUID.randomUUID(), "req-1", AuditLogWritePort.Result.SUCCESS, null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> entry(Map.of("reason", "x".repeat(161))));
	}

	private static AuditLogWritePort.AuditLogEntry entry(Map<String, String> metadata) {
		return new AuditLogWritePort.AuditLogEntry(
			Instant.parse("2026-08-15T03:04:05Z"), UUID.randomUUID(), AuditLogWritePort.ActorType.USER,
			"LIQUIDITY_HOLD_CREATED", "LIQUIDITY_HOLD", UUID.randomUUID(), UUID.randomUUID(),
			"req-audit-001", AuditLogWritePort.Result.SUCCESS, null, metadata);
	}
}
