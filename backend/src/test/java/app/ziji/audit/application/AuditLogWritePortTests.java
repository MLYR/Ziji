package app.ziji.audit.application;

import java.time.Instant;
import java.util.LinkedHashMap;
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
	void acceptsSystemActorAndDefensivelyCopiesUnicodeMetadata() {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put("summary", "😀".repeat(160));
		AuditLogWritePort.AuditLogEntry entry = new AuditLogWritePort.AuditLogEntry(
			Instant.parse("2026-08-15T03:04:05Z"), null, AuditLogWritePort.ActorType.SYSTEM,
			"LIQUIDITY_HOLD_EXPIRED", "LIQUIDITY_HOLD", UUID.randomUUID(), UUID.randomUUID(),
			"req-audit-system-001", AuditLogWritePort.Result.SUCCESS, "EXPIRED", metadata);

		metadata.put("summary", "changed");
		assertEquals("😀".repeat(160), entry.metadata().get("summary"));
		assertThrows(UnsupportedOperationException.class, () -> entry.metadata().put("version", "2"));
	}

	@Test
	void rejectsUnsafeOrIncompleteAppendFacts() {
		assertThrows(IllegalArgumentException.class, () -> entry(null));
		assertThrows(IllegalArgumentException.class, () -> new AuditLogWritePort.AuditLogEntry(
			Instant.now(), null, AuditLogWritePort.ActorType.USER, "ACTION", "HOLD", UUID.randomUUID(),
			UUID.randomUUID(), "req-1", AuditLogWritePort.Result.SUCCESS, null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new AuditLogWritePort.AuditLogEntry(
			Instant.now(), UUID.randomUUID(), AuditLogWritePort.ActorType.SYSTEM, "ACTION", "HOLD", UUID.randomUUID(),
			UUID.randomUUID(), "req-1", AuditLogWritePort.Result.SUCCESS, null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> entry(Map.of("summary", "😀".repeat(161))));
		assertThrows(IllegalArgumentException.class, () -> entry(Map.of("accessToken", "secret")));
		assertThrows(IllegalArgumentException.class, () -> entry(Map.of("reason", "人工冻结")));
		assertThrows(IllegalArgumentException.class, () -> new AuditLogWritePort.AuditLogEntry(
			Instant.now(), UUID.randomUUID(), AuditLogWritePort.ActorType.USER, "human action", "HOLD", UUID.randomUUID(),
			UUID.randomUUID(), "req-1", AuditLogWritePort.Result.SUCCESS, null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new AuditLogWritePort.AuditLogEntry(
			Instant.now(), UUID.randomUUID(), AuditLogWritePort.ActorType.USER, "ACTION", "HOLD", UUID.randomUUID(),
			UUID.randomUUID(), "req-1", AuditLogWritePort.Result.SUCCESS, "人工理由", Map.of()));
	}

	private static AuditLogWritePort.AuditLogEntry entry(Map<String, String> metadata) {
		return new AuditLogWritePort.AuditLogEntry(
			Instant.parse("2026-08-15T03:04:05Z"), UUID.randomUUID(), AuditLogWritePort.ActorType.USER,
			"LIQUIDITY_HOLD_CREATED", "LIQUIDITY_HOLD", UUID.randomUUID(), UUID.randomUUID(),
			"req-audit-001", AuditLogWritePort.Result.SUCCESS, null, metadata);
	}
}
