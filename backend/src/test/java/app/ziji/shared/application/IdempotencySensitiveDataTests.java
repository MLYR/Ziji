package app.ziji.shared.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 幂等 DTO、异常和结果只暴露安全状态，不将 Key、Hash、匿名主体或完整业务响应写入字符串表示。 */
class IdempotencySensitiveDataTests {

	@Test
	void publicResultAndExceptionStringsDoNotExposeRequestSecrets() {
		String key = "idempotency-key-secret-value";
		String hash = "a".repeat(64);
		IdempotencyRequest request = IdempotencyRequest.authenticated(
			UUID.randomUUID(), 1, "postTransaction", key, hash);
		IdempotencyExecution<String> result = IdempotencyExecution.executed(
			"full-response-must-not-be-rendered", IdempotencyResponse.failedFinal(422, "BUSINESS_RULE"));
		IdempotencyValidationException exception = new IdempotencyValidationException("幂等请求无效。");

		assertFalse(request.toString().contains(key));
		assertFalse(request.toString().contains(hash));
		assertFalse(result.toString().contains("full-response-must-not-be-rendered"));
		assertFalse(result.toString().contains("BUSINESS_RULE"));
		assertFalse(exception.getMessage().contains(key));
		assertFalse(exception.getMessage().contains(hash));
	}
}
