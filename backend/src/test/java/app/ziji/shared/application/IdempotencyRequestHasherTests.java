package app.ziji.shared.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** API §2.4 请求 Hash 单元测试：同语义稳定、不同语义不碰撞，且不接受浮点或未类型化对象。 */
class IdempotencyRequestHasherTests {

	@Test
	void recursivelySortsObjectKeysButPreservesArraysAndDistinguishesNullFromMissing() {
		Map<String, Object> leftNested = new LinkedHashMap<>();
		leftNested.put("z", null);
		leftNested.put("a", List.of("first", "second"));
		Map<String, Object> left = new LinkedHashMap<>();
		left.put("zeta", leftNested);
		left.put("alpha", true);

		Map<String, Object> rightNested = new LinkedHashMap<>();
		rightNested.put("a", List.of("first", "second"));
		rightNested.put("z", null);
		Map<String, Object> right = new LinkedHashMap<>();
		right.put("alpha", true);
		right.put("zeta", rightNested);

		assertEquals(hash(left, null), hash(right, null));
		assertNotEquals(hash(left, null), hash(Map.of("alpha", true, "zeta", List.of("second", "first")), null));
		Map<String, Object> explicitNull = new LinkedHashMap<>();
		explicitNull.put("value", null);
		assertNotEquals(hash(Map.of("value", "present"), null), hash(explicitNull, null));
	}

	@Test
	void canonicalizesTypedDecimalsUuidsDatesAndInstantsWithoutChangingOrdinaryStrings() {
		UUID uuid = UUID.fromString("A0A00000-0000-0000-0000-000000000001");
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("amount", new BigDecimal("001.2300"));
		first.put("id", uuid);
		first.put("date", LocalDate.parse("2026-08-15"));
		first.put("at", OffsetDateTime.parse("2026-08-15T08:00:00+08:00"));
		first.put("accountNo", "00123");

		Map<String, Object> second = new LinkedHashMap<>();
		second.put("amount", new BigDecimal("1.23"));
		second.put("id", UUID.fromString("a0a00000-0000-0000-0000-000000000001"));
		second.put("date", LocalDate.parse("2026-08-15"));
		second.put("at", Instant.parse("2026-08-15T00:00:00Z"));
		second.put("accountNo", "00123");

		assertEquals(hash(first, "\"7\""), hash(second, "\"7\""));
		second.put("accountNo", "123");
		assertNotEquals(hash(first, "\"7\""), hash(second, "\"7\""));
		assertNotEquals(hash(first, null), hash(first, "\"7\""));
	}

	@Test
	void normalizesMethodAndMediaTypeAndHashesVerifiedBinaryMetadataInsteadOfFileContents() {
		Map<String, Object> first = Map.of("file", IdempotencyRequestHasher.binaryPart(
			"Application/Pdf; charset=UTF-8", 123L, "a".repeat(64)));
		Map<String, Object> second = Map.of("file", IdempotencyRequestHasher.binaryPart(
			"application/pdf", 123L, "a".repeat(64)));

		assertEquals(
			IdempotencyRequestHasher.hash("post", "Application/Json; charset=UTF-8", "/api/v1/files/abc", first, null),
			IdempotencyRequestHasher.hash("POST", "application/json", "/api/v1/files/abc", second, null));
		assertNotEquals(hash(first, null), hash(Map.of("file", IdempotencyRequestHasher.binaryPart(
			"application/pdf", 124L, "a".repeat(64))), null));
	}

	@Test
	void rejectsAmbiguousOrUnsupportedPayloadRepresentationsBeforeAnyRecordCanBeCreated() {
		assertThrows(IdempotencyValidationException.class, () -> hash(Map.of("amount", 1.2d), null));
		assertThrows(IdempotencyValidationException.class, () -> hash(Map.of("nested", new Object()), null));
		assertThrows(IdempotencyValidationException.class, () -> IdempotencyRequestHasher.hash(
			"POST", "application/json", "/api/v1/test", new Object[] { "ok", null }, "\ud800"));
	}

	private static String hash(Object payload, String ifMatch) {
		return IdempotencyRequestHasher.hash("POST", "application/json", "/api/v1/resources/42", payload, ifMatch);
	}
}
