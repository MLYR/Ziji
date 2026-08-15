package app.ziji.shared.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
		assertThrows(IdempotencyValidationException.class, () -> IdempotencyResponse.succeededResource(
			201, "user", UUID.randomUUID(), null));

		IdempotencyResponse response = IdempotencyResponse.failedFinal(422, "BUSINESS_RULE");
		assertFalse(response.toString().contains("BUSINESS_RULE"));
	}
}
