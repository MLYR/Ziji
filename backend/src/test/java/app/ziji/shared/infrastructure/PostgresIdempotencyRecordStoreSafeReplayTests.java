package app.ziji.shared.infrastructure;

import java.time.Instant;
import java.util.UUID;

import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** V016 历史畸形安全引用只能 fail closed，不能由适配器查询当前资源修补。 */
class PostgresIdempotencyRecordStoreSafeReplayTests {

	private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

	@Test
	void malformedUnknownOrInconsistentFinalReferenceFailsClosedWithoutLeakingStoredJson() {
		assertSafeReplayUnavailable("{not-json");
		assertSafeReplayUnavailable("""
			{"kind":"UNSUPPORTED","errorCode":"INTERNAL_ERROR","secret":"must-not-leak"}
			""");
		assertSafeReplayUnavailable("""
			{"kind":"PROBLEM","errorCode":"VERSION_CONFLICT"}
			""");
		assertSafeReplayUnavailable("""
			{"kind":"VERSION_CONFLICT","errorCode":"VERSION_CONFLICT","currentVersion":7,
			"currentEtag":"\\"8\\"","resourceLocation":"/api/v1/transactions/1"}
			""");
	}

	private static void assertSafeReplayUnavailable(String responseReferenceJson) {
		PostgresIdempotencyRecordStore store = new PostgresIdempotencyRecordStore(
			DSL.using(SQLDialect.POSTGRES), new ObjectMapper());
		IdempotencyRequest request = IdempotencyRequest.authenticated(
			UUID.randomUUID(), 1, "applySyncOperations", "idempotency-key-0001", "a".repeat(64));
		// 历史 NOT VALID 行可能绕过 V016；解析失败必须停止重放且不携带原始 JSON。
		IdempotencyRecordStore.Acquisition acquisition = store.resolveLocked(
			new PostgresIdempotencyRecordStore.StoredRecord(
				UUID.randomUUID(), request.requestHash(), "FAILED_FINAL", 409, responseReferenceJson,
				null, null, null, null), request, NOW);

		assertInstanceOf(IdempotencyRecordStore.Acquisition.SafeReplayUnavailable.class, acquisition);
		assertFalse(acquisition.toString().contains("secret"));
	}
}
