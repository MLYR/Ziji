package app.ziji.shared.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V009 response_reference 只能保存有界安全引用，不能借幂等表保存完整响应或敏感字段。 */
class IdempotencyResponseTests {

	@Test
	void rejectsOversizedOrUnsafeReferencesAndKeepsToStringFreeOfResponseContent() {
		assertThrows(IdempotencyValidationException.class, () -> new IdempotencyResponse.ResourceReference(
			"/" + "x".repeat(8_192), null, null));
		assertThrows(IdempotencyValidationException.class, () -> new IdempotencyResponse.ResourceReference(
			"//outside.example/test", null, null));
		assertThrows(IdempotencyValidationException.class, () -> IdempotencyResponse.failedFinal(422, "password=secret"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinal(409, "VERSION_CONFLICT"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedRetryable(500, "VERSION_CONFLICT"));
		assertThrows(IdempotencyValidationException.class, () -> IdempotencyResponse.succeededResource(
			201, "user", UUID.randomUUID(), null));

		IdempotencyResponse response = IdempotencyResponse.failedFinal(422, "BUSINESS_RULE");
		assertFalse(response.toString().contains("BUSINESS_RULE"));
	}

	@Test
	void versionConflictReferenceDerivesStableEtagAndRejectsUnsafeInputs() {
		IdempotencyResponse response = IdempotencyResponse.failedFinalVersionConflict(
			409, 7, "/api/v1/transactions/4f6ba6c8-0a3c-4bd2-9313-d11850b3f73f");

		IdempotencyResponse.VersionConflictReference reference = assertInstanceOf(
			IdempotencyResponse.VersionConflictReference.class, response.reference());
		assertEquals("VERSION_CONFLICT", reference.errorCode());
		assertEquals(7, reference.currentVersion());
		assertEquals("\"7\"", reference.currentEtag());
		assertEquals("/api/v1/transactions/4f6ba6c8-0a3c-4bd2-9313-d11850b3f73f", reference.resourceLocation());
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(422, 7, "/api/v1/transactions/1"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 0, "/api/v1/transactions/1"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 10_000_000_000L, "/api/v1/transactions/1"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, ""));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, null));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, "https://outside.example/transaction"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, "//outside.example/transaction"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, "/api//v1/transactions/1"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, "/transaction\n1"));
		assertThrows(IdempotencyValidationException.class,
			() -> IdempotencyResponse.failedFinalVersionConflict(409, 1, "/" + "x".repeat(512)));
		assertInstanceOf(IdempotencyResponse.ProblemReference.class,
			IdempotencyResponse.failedFinal(422, "BUSINESS_RULE").reference());
	}
}
