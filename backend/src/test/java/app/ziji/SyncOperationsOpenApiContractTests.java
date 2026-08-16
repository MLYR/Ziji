package app.ziji;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** CHG-SYNC-003 契约基线：离线上传只暴露可安全映射的 Transaction 三分支。 */
class SyncOperationsOpenApiContractTests {

	@Test
	void applySyncOperationsLocksTheRouteResponsesAndThreeOperationBranches() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> schemas = schemas(document);
		Map<String, Object> operation = objectMap(
			objectMap(objectMap(document.get("paths"), "OpenAPI paths").get("/sync/operations"), "sync operations path")
				.get("post"), "POST /sync/operations");

		assertEquals("applySyncOperations", operation.get("operationId"));
		assertEquals(Set.of("200", "400", "401", "403"),
			objectMap(operation.get("responses"), "applySyncOperations responses").keySet());
		assertEquals("#/components/responses/SyncOperationResultsOk", responseRef(operation, "200"));

		Map<String, Object> syncOperation = objectMap(schemas.get("SyncOperation"), "SyncOperation");
		assertEquals(List.of(
			"#/components/schemas/SyncCreateTransactionOperation",
			"#/components/schemas/SyncUpdateTransactionOperation",
			"#/components/schemas/SyncReverseTransactionOperation"), refs(syncOperation, "oneOf"));
		Map<String, Object> mapping = objectMap(
			objectMap(syncOperation.get("discriminator"), "SyncOperation discriminator").get("mapping"),
			"SyncOperation discriminator mapping");
		assertEquals(Set.of("CREATE", "UPDATE", "REVERSE"), mapping.keySet());

		assertTransactionOperation(schemas, "SyncCreateTransactionOperation", "CREATE", "null",
			"#/components/schemas/SyncCreateTransactionPayload");
		assertTransactionOperation(schemas, "SyncUpdateTransactionOperation", "UPDATE", "integer",
			"#/components/schemas/SyncUpdateTransactionPayload");
		assertTransactionOperation(schemas, "SyncReverseTransactionOperation", "REVERSE", "integer",
			"#/components/schemas/ReasonRequest");
		assertCreateAndUpdatePayloads(schemas);
	}

	@Test
	void syncResultsDoNotPretendTheAsynchronousChangeLogIsSynchronous() throws IOException {
		Map<String, Object> schemas = schemas(readContract());
		Map<String, Object> result = objectMap(schemas.get("SyncOperationResult"), "SyncOperationResult");
		assertEquals(List.of(
			"#/components/schemas/SyncAppliedOperationResult",
			"#/components/schemas/SyncDuplicateOperationResult",
			"#/components/schemas/SyncConflictOperationResult",
			"#/components/schemas/SyncRejectedOperationResult",
			"#/components/schemas/SyncRetryableOperationResult"), refs(result, "oneOf"));
		assertFalse(result.containsKey("properties"));

		Map<String, Object> envelope = objectMap(schemas.get("SyncOperationResultsEnvelope"), "SyncOperationResultsEnvelope");
		Map<String, Object> data = objectMap(objectMap(envelope.get("properties"), "SyncOperationResultsEnvelope properties")
			.get("data"), "SyncOperationResultsEnvelope data");
		Map<String, Object> dataProperties = objectMap(data.get("properties"), "SyncOperationResultsEnvelope data properties");
		assertEquals(Set.of("results"), dataProperties.keySet());

		Map<String, Object> conflict = objectMap(schemas.get("SyncConflictOperationResult"), "SyncConflictOperationResult");
		assertEquals("#/components/schemas/SyncConflictProblem", objectMap(
			objectMap(conflict.get("properties"), "SyncConflictOperationResult properties").get("error"),
			"SyncConflictOperationResult error").get("$ref"));
		Map<String, Object> safeConflict = objectMap(schemas.get("SyncConflictProblem"), "SyncConflictProblem");
		Map<String, Object> safetyProperties = objectMap(
			objectMap(((List<?>) safeConflict.get("allOf")).get(1), "SyncConflictProblem restriction")
				.get("properties"), "SyncConflictProblem properties");
		assertEquals("VERSION_CONFLICT", objectMap(safetyProperties.get("code"), "SyncConflictProblem code").get("const"));
		assertEquals("#/components/schemas/VersionConflictDetails", objectMap(
			safetyProperties.get("versionConflict"), "SyncConflictProblem versionConflict").get("$ref"));
		assertFalse(safetyProperties.containsKey("currentResource"));
		Map<String, Object> duplicate = objectMap(schemas.get("SyncDuplicateOperationResult"), "SyncDuplicateOperationResult");
		assertEquals(List.of(
			"#/components/schemas/SyncDuplicateAppliedOperationResult",
			"#/components/schemas/SyncDuplicateFailedOperationResult"), refs(duplicate, "oneOf"));
		Map<String, Object> duplicateFailed = objectMap(schemas.get("SyncDuplicateFailedOperationResult"), "SyncDuplicateFailedOperationResult");
		assertEquals(List.of(
			"#/components/schemas/SyncConflictProblem",
			"#/components/schemas/SyncRejectedProblem"), refs(objectMap(
			objectMap(duplicateFailed.get("properties"), "SyncDuplicateFailedOperationResult properties").get("error"),
			"SyncDuplicateFailedOperationResult error"), "oneOf"));
		Map<String, Object> rejected = objectMap(schemas.get("SyncRejectedOperationResult"), "SyncRejectedOperationResult");
		assertEquals("#/components/schemas/SyncRejectedProblem", objectMap(
			objectMap(rejected.get("properties"), "SyncRejectedOperationResult properties").get("error"),
			"SyncRejectedOperationResult error").get("$ref"));
		Map<String, Object> rejectedProblemProperties = objectMap(
			objectMap(schemas.get("SyncRejectedProblem"), "SyncRejectedProblem").get("properties"),
			"SyncRejectedProblem properties");
		assertFalse(rejectedProblemProperties.containsKey("versionConflict"));
		assertEquals("VERSION_CONFLICT", objectMap(objectMap(rejectedProblemProperties.get("code"),
			"SyncRejectedProblem code").get("not"), "SyncRejectedProblem code prohibition").get("const"));
		Map<String, Object> retryable = objectMap(schemas.get("SyncRetryableOperationResult"), "SyncRetryableOperationResult");
		Map<String, Object> retryableProperties = objectMap(retryable.get("properties"), "SyncRetryableOperationResult properties");
		assertEquals("RETRYABLE", objectMap(retryableProperties.get("status"), "SyncRetryableOperationResult status").get("const"));
		assertEquals(5, objectMap(retryableProperties.get("retryAfterSeconds"), "SyncRetryableOperationResult retryAfterSeconds").get("const"));
		assertEquals("#/components/schemas/SyncRetryableProblem", objectMap(
			retryableProperties.get("error"), "SyncRetryableOperationResult error").get("$ref"));
		Map<String, Object> retryableProblem = objectMap(schemas.get("SyncRetryableProblem"), "SyncRetryableProblem");
		assertEquals(List.of(
			"#/components/schemas/SyncRetryableInProgressProblem",
			"#/components/schemas/SyncRetryableInternalErrorProblem"), refs(retryableProblem, "oneOf"));
		assertRetryableProblem(schemas, "SyncRetryableInProgressProblem", 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
		assertRetryableProblem(schemas, "SyncRetryableInternalErrorProblem", 500, "INTERNAL_ERROR");
	}

	private static void assertRetryableProblem(Map<String, Object> schemas, String schemaName, int status, String code) {
		List<?> allOf = list(objectMap(schemas.get(schemaName), schemaName).get("allOf"), schemaName + " allOf");
		Map<String, Object> base = objectMap(allOf.getFirst(), schemaName + " base");
		assertEquals("#/components/schemas/SyncRetryableProblemBase", base.get("$ref"));
		Map<String, Object> properties = objectMap(objectMap(allOf.get(1), schemaName + " restriction")
			.get("properties"), schemaName + " properties");
		assertEquals(status, objectMap(properties.get("status"), schemaName + " status").get("const"));
		assertEquals(code, objectMap(properties.get("code"), schemaName + " code").get("const"));
	}

	private static void assertTransactionOperation(
		Map<String, Object> schemas,
		String schemaName,
		String operationType,
		String baseVersionType,
		String payloadRef) {
		Map<String, Object> properties = objectMap(
			objectMap(schemas.get(schemaName), schemaName).get("properties"), schemaName + " properties");
		assertEquals("TRANSACTION", objectMap(properties.get("entityType"), schemaName + " entityType").get("const"));
		assertEquals(operationType, objectMap(properties.get("operationType"), schemaName + " operationType").get("const"));
		assertEquals(baseVersionType, objectMap(properties.get("baseVersion"), schemaName + " baseVersion").get("type"));
		assertEquals(payloadRef, objectMap(properties.get("payload"), schemaName + " payload").get("$ref"));
	}

	private static void assertCreateAndUpdatePayloads(Map<String, Object> schemas) {
		Map<String, Object> createPayload = objectMap(schemas.get("SyncCreateTransactionPayload"), "SyncCreateTransactionPayload");
		List<String> expectedTransactionSchemas = List.of(
			"#/components/schemas/SyncIncomeTransactionRequest",
			"#/components/schemas/SyncExpenseTransactionRequest",
			"#/components/schemas/SyncRefundTransactionRequest",
			"#/components/schemas/SyncTransferTransactionRequest");
		assertEquals(expectedTransactionSchemas, refs(createPayload, "oneOf"));
		for (String reference : expectedTransactionSchemas) {
			String schemaName = reference.substring(reference.lastIndexOf('/') + 1);
			assertFalse(objectMap(objectMap(schemas.get(schemaName), schemaName).get("properties"), schemaName + " properties")
				.containsKey("id"));
		}

		Map<String, Object> updatePayload = objectMap(schemas.get("SyncUpdateTransactionPayload"), "SyncUpdateTransactionPayload");
		assertEquals("#/components/schemas/SyncCreateTransactionPayload", objectMap(
			objectMap(updatePayload.get("properties"), "SyncUpdateTransactionPayload properties").get("replacement"),
			"SyncUpdateTransactionPayload replacement").get("$ref"));
	}

	private static String responseRef(Map<String, Object> operation, String status) {
		return String.valueOf(objectMap(objectMap(operation.get("responses"), "operation responses").get(status),
			"operation " + status + " response").get("$ref"));
	}

	private static List<String> refs(Map<String, Object> schema, String field) {
		return list(schema.get(field), field).stream()
			.map(branch -> String.valueOf(objectMap(branch, field + " branch").get("$ref")))
			.toList();
	}

	private static Map<String, Object> schemas(Map<String, Object> document) {
		return objectMap(objectMap(document.get("components"), "OpenAPI components").get("schemas"), "OpenAPI schemas");
	}

	private static Map<String, Object> readContract() throws IOException {
		for (Path candidate : List.of(Path.of("../openapi/ziji-v1.yaml"), Path.of("openapi/ziji-v1.yaml"))) {
			if (Files.isRegularFile(candidate)) {
				try (InputStream input = Files.newInputStream(candidate)) {
					return new Yaml().load(input);
				}
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

	private static List<?> list(Object value, String label) {
		if (!(value instanceof List<?> list)) {
			throw new IllegalStateException(label + " 必须是数组");
		}
		return list;
	}
}
