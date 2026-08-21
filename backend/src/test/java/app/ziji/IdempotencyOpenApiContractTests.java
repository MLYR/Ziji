package app.ziji;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHG-SYNC-001 契约基线：每个幂等写接口都声明统一的 409 与固定 Retry-After 语义。 */
class IdempotencyOpenApiContractTests {

	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace");

	@Test
	void allIdempotencyKeyOperationsReferenceConflictWithFixedRetryAfter() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> components = objectMap(document.get("components"), "OpenAPI components");
		Map<String, Object> componentResponses = objectMap(components.get("responses"), "OpenAPI component responses");
		Map<String, Object> conflict = objectMap(componentResponses.get("Conflict"), "Conflict response");
		Map<String, Object> accountArchiveConflict = objectMap(
			componentResponses.get("AccountArchiveConflict"), "AccountArchiveConflict response");
		assertFixedRetryAfter(conflict);
		assertFixedRetryAfter(accountArchiveConflict);

		int idempotencyOperationCount = 0;
		Map<String, Object> paths = objectMap(document.get("paths"), "OpenAPI paths");
		for (Map.Entry<String, Object> path : paths.entrySet()) {
			Map<String, Object> pathItem = objectMap(path.getValue(), "OpenAPI path " + path.getKey());
			for (Map.Entry<String, Object> operation : pathItem.entrySet()) {
				if (!HTTP_METHODS.contains(operation.getKey().toLowerCase(Locale.ROOT))) {
					continue;
				}
				Map<String, Object> definition = objectMap(operation.getValue(),
					operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
				if (!hasIdempotencyKey(pathItem) && !hasIdempotencyKey(definition)) {
					continue;
				}
				idempotencyOperationCount++;
				String operationId = requiredOperationId(definition, operation.getKey(), path.getKey());
				Map<String, Object> responses = objectMap(definition.get("responses"), operationId + " responses");
				Map<String, Object> conflictResponse = objectMap(responses.get("409"), operationId + " 409 response");
				// 归档的 409 还要约束账户专用错误码，其 Retry-After 仍复用统一幂等门禁。
				String expectedConflict = "archiveAccount".equals(operationId)
					? "#/components/responses/AccountArchiveConflict"
					: "#/components/responses/Conflict";
				assertEquals(expectedConflict, conflictResponse.get("$ref"),
					operationId + " 必须引用符合统一幂等门禁的冲突响应");
			}
		}

		// 独立负债详情的 PUT/PATCH 都是写操作，必须纳入统一幂等契约门禁。
		assertEquals(40, idempotencyOperationCount, "Idempotency-Key operation 覆盖数必须和冻结基线一致");
	}

	@Test
	void idempotencyKeyDescriptionPreservesAuthenticatedAndAnonymousSubjects() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> components = objectMap(document.get("components"), "OpenAPI components");
		Map<String, Object> parameters = objectMap(components.get("parameters"), "OpenAPI parameters");
		Map<String, Object> idempotencyKey = objectMap(parameters.get("IdempotencyKey"), "Idempotency-Key parameter");
		String description = String.valueOf(idempotencyKey.get("description"));

		// 全局参数同时服务认证写操作和公开注册/重置，描述不能回归为单一当前用户主体。
		assertTrue(description.contains("当前用户"));
		assertTrue(description.contains("registerUser"));
		assertTrue(description.contains("resetPassword"));
		assertTrue(description.contains("匿名主体"));
		assertTrue(description.contains("IDEMPOTENCY_KEY_REUSED"));
	}

	private static Map<String, Object> readContract() throws IOException {
		Path contract = locateContract();
		try (InputStream input = Files.newInputStream(contract)) {
			return new Yaml().load(input);
		}
	}

	private static void assertFixedRetryAfter(Map<String, Object> conflict) {
		Map<String, Object> headers = objectMap(conflict.get("headers"), "Conflict response headers");
		Map<String, Object> retryAfter = objectMap(headers.get("Retry-After"), "Conflict Retry-After header");
		Map<String, Object> schema = objectMap(retryAfter.get("schema"), "Conflict Retry-After schema");
		assertEquals(5, schema.get("const"));
		assertTrue(String.valueOf(retryAfter.get("description")).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
	}

	private static boolean hasIdempotencyKey(Map<String, Object> definition) {
		Object value = definition.get("parameters");
		if (!(value instanceof List<?> parameters)) {
			return false;
		}
		return parameters.stream().anyMatch(parameter -> {
			Map<String, Object> parameterDefinition = objectMap(parameter, "OpenAPI parameter");
			return "#/components/parameters/IdempotencyKey".equals(parameterDefinition.get("$ref"))
				|| ("Idempotency-Key".equals(parameterDefinition.get("name"))
					&& "header".equals(parameterDefinition.get("in")));
		});
	}

	private static String requiredOperationId(Map<String, Object> definition, String method, String path) {
		Object value = definition.get("operationId");
		if (!(value instanceof String operationId) || operationId.isBlank()) {
			throw new IllegalStateException("OpenAPI 操作缺少 operationId: " + method.toUpperCase(Locale.ROOT) + " " + path);
		}
		return operationId;
	}

	private static Path locateContract() throws IOException {
		for (Path candidate : List.of(Path.of("../openapi/ziji-v1.yaml"), Path.of("openapi/ziji-v1.yaml"))) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toRealPath();
			}
		}
		throw new IllegalStateException("未找到 openapi/ziji-v1.yaml；请从仓库根目录或 backend 目录运行测试");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> objectMap(Object value, String label) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalStateException(label + " 必须是对象");
		}
		return new LinkedHashMap<>((Map<String, Object>) map);
	}
}
