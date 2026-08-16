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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-API-005 契约基线：冻结 LiquidityHold 的机器字段、生命周期、权限和错误语义。 */
class LiquidityHoldOpenApiContractTests {

	private static final Map<String, String> ROUTES = Map.of(
		"listLiquidityHolds", "GET /accounts/{accountId}/liquidity-holds",
		"createLiquidityHold", "POST /accounts/{accountId}/liquidity-holds",
		"reviseLiquidityHold", "POST /accounts/{accountId}/liquidity-holds/{holdId}/revisions",
		"releaseLiquidityHold", "POST /accounts/{accountId}/liquidity-holds/{holdId}/release");

	private static final Set<String> LIST_ERRORS = Set.of("200", "400", "401", "403", "404");
	private static final Set<String> CREATE_ERRORS = Set.of("201", "400", "401", "403", "404", "409", "422");
	private static final Set<String> REVISE_ERRORS = Set.of("201", "400", "401", "403", "404", "409", "422");
	private static final Set<String> RELEASE_ERRORS = Set.of("200", "400", "401", "403", "404", "409", "422");

	@Test
	void liquidityHoldOperationsFreezeRoutesHeadersErrorsAndSemantics() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> paths = objectMap(document.get("paths"), "OpenAPI paths");
		Map<String, Object> schemas = objectMap(
			objectMap(document.get("components"), "OpenAPI components").get("schemas"), "OpenAPI schemas");

		// 机器契约必须逐项锁住，避免业务实现凭相似路径或中文摘要猜测接口。
		assertRoute(paths, "listLiquidityHolds", "GET", "/accounts/{accountId}/liquidity-holds");
		assertRoute(paths, "createLiquidityHold", "POST", "/accounts/{accountId}/liquidity-holds");
		assertRoute(paths, "reviseLiquidityHold", "POST", "/accounts/{accountId}/liquidity-holds/{holdId}/revisions");
		assertRoute(paths, "releaseLiquidityHold", "POST", "/accounts/{accountId}/liquidity-holds/{holdId}/release");

		Map<String, Object> list = operation(paths, "/accounts/{accountId}/liquidity-holds", "get");
		Map<String, Object> create = operation(paths, "/accounts/{accountId}/liquidity-holds", "post");
		Map<String, Object> revise = operation(paths, "/accounts/{accountId}/liquidity-holds/{holdId}/revisions", "post");
		Map<String, Object> release = operation(paths, "/accounts/{accountId}/liquidity-holds/{holdId}/release", "post");

		assertEquals(LIST_ERRORS, responseKeys(list));
		assertEquals(CREATE_ERRORS, responseKeys(create));
		assertEquals(REVISE_ERRORS, responseKeys(revise));
		assertEquals(RELEASE_ERRORS, responseKeys(release));
		assertEquals(List.of("VALIDATION_ERROR", "AUTHENTICATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND"),
			list.get("x-error-codes"));
		assertEquals(List.of("VALIDATION_ERROR", "AUTHENTICATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND",
			"IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_IN_PROGRESS", "BUSINESS_RULE_VIOLATION"),
			create.get("x-error-codes"));
		assertEquals(List.of("VALIDATION_ERROR", "AUTHENTICATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND",
			"VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_IN_PROGRESS", "BUSINESS_RULE_VIOLATION"),
			revise.get("x-error-codes"));
		assertEquals(revise.get("x-error-codes"), release.get("x-error-codes"));
		assertHasParameter(create, "#/components/parameters/IdempotencyKey");
		assertHasParameter(revise, "#/components/parameters/IdempotencyKey");
		assertHasParameter(revise, "#/components/parameters/LiquidityHoldIfMatch");
		assertHasParameter(release, "#/components/parameters/IdempotencyKey");
		assertHasParameter(release, "#/components/parameters/LiquidityHoldIfMatch");

		Map<String, Object> createRequest = schema(schemas, "CreateLiquidityHoldRequest");
		Map<String, Object> reviseRequest = schema(schemas, "ReviseLiquidityHoldRequest");
		assertRequestSchema(createRequest, Set.of("type", "amount", "currency", "effectiveAt", "reason"));
		assertRequestSchema(reviseRequest, Set.of("type", "amount", "currency", "effectiveAt", "reason"));
		assertTypeEnum(createRequest);
		assertTypeEnum(reviseRequest);
		assertMoneyReferences(createRequest);
		assertMoneyReferences(reviseRequest);
		assertFalse(properties(createRequest).containsKey("source"));
		assertFalse(properties(createRequest).containsKey("revisionReason"));
		assertFalse(properties(reviseRequest).containsKey("source"));
		assertFalse(properties(reviseRequest).containsKey("revisionReason"));

		Map<String, Object> hold = schema(schemas, "LiquidityHold");
		assertEquals(Boolean.FALSE, hold.get("additionalProperties"));
		assertEquals(Set.of(
			"id", "accountId", "rootHoldId", "supersedesId", "revisionNo", "type", "amount", "currency",
			"status", "effectiveAt", "expiresAt", "source", "reason", "createdBy", "createdAt", "updatedAt",
			"releasedAt", "endedAt", "endReason", "version"), newSet(hold.get("required")));
		assertEquals(Set.of("FROZEN", "IN_TRANSIT", "RESERVED"),
			newSet(properties(hold).get("type"), "enum"));
		assertEquals(Set.of("PENDING", "ACTIVE", "RELEASED", "SUPERSEDED", "EXPIRED"),
			newSet(properties(hold).get("status"), "enum"));
		assertEquals(Set.of("MANUAL", "IMPORT", "SYSTEM"),
			newSet(properties(hold).get("source"), "enum"));
		Set<String> endReasons = newSet(properties(hold).get("endReason"), "enum");
		assertEquals(Set.of("RELEASED", "SUPERSEDED", "EXPIRED"),
			endReasons.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
		assertTrue(endReasons.contains(null));

		Map<String, Object> auditMapping = objectMap(hold.get("x-audit-mapping"), "LiquidityHold audit mapping");
		assertEquals("MANUAL", objectMap(auditMapping.get("source"), "source mapping").get("publicWriteValue"));
		assertEquals("note", objectMap(auditMapping.get("reason"), "reason mapping").get("databaseColumn"));
		assertEquals("LIQUIDITY_HOLD_REVISED",
			objectMap(auditMapping.get("revisionAudit"), "revision audit mapping").get("action"));

		Map<String, Object> lifecycle = objectMap(hold.get("x-lifecycle"), "LiquidityHold lifecycle");
		assertEquals("REQUEST_EVALUATION_TIME", lifecycle.get("asOf"));
		assertTrue(String.valueOf(lifecycle.get("validWhen")).contains("effective_at <= asOf"));
		assertTrue(String.valueOf(lifecycle.get("expiry")).contains("ended_at=expires_at"));
		assertTrue(String.valueOf(lifecycle.get("concurrency")).contains("VERSION_CONFLICT"));

		Map<String, Object> pagination = objectMap(list.get("x-pagination"), "LiquidityHold pagination");
		assertEquals("FULL_REVISION_HISTORY", pagination.get("dataset"));
		assertEquals(List.of("created_at DESC", "id DESC"), pagination.get("order"));
		assertEquals("OPAQUE_KEYSET_BOUND_TO_ACCOUNT_FILTER_AND_ORDER", pagination.get("cursor"));
		assertEquals(List.of("OWNER", "EDITOR", "VIEWER"),
			objectMap(list.get("x-permission-matrix"), "list permissions").get("read"));
		assertEquals(List.of("OWNER", "EDITOR"),
			objectMap(create.get("x-permission-matrix"), "create permissions").get("write"));
		assertActiveReadWriteMatrix(revise);
		assertActiveReadWriteMatrix(release);
		assertIdempotency(create, "ACTUAL_ACCOUNT_ID_TYPED_PAYLOAD_TYPE_AMOUNT_CURRENCY_EFFECTIVE_AT_EXPIRES_AT_REASON_EXPLICIT_ABSENT_IF_MATCH");
		assertIdempotency(revise, "ACTUAL_ACCOUNT_ID_AND_HOLD_ID_TYPED_PAYLOAD_TYPE_AMOUNT_CURRENCY_EFFECTIVE_AT_EXPIRES_AT_REASON_IF_MATCH");
		assertIdempotency(release, "ACTUAL_ACCOUNT_ID_AND_HOLD_ID_TYPED_EMPTY_PAYLOAD_IF_MATCH");
		assertTrue(String.valueOf(objectMap(create.get("x-idempotency"), "create idempotency").get("requestHash")).contains("CURRENCY"));
		assertTrue(String.valueOf(objectMap(revise.get("x-idempotency"), "revise idempotency").get("requestHash")).contains("CURRENCY"));

		assertEnvelopeSchema(schemas, "LiquidityHoldEnvelope", false);
		assertEnvelopeSchema(schemas, "LiquidityHoldListEnvelope", true);
	}

	@Test
	void sharedHeaderAndErrorContractRejectsWeakOrUnboundedIfMatch() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> components = objectMap(document.get("components"), "OpenAPI components");
		Map<String, Object> parameters = objectMap(components.get("parameters"), "OpenAPI parameters");
		Map<String, Object> ifMatch = objectMap(parameters.get("LiquidityHoldIfMatch"), "LiquidityHold If-Match parameter");
		Map<String, Object> schema = objectMap(ifMatch.get("schema"), "If-Match schema");

		assertEquals("^\"[1-9][0-9]*\"$", schema.get("pattern"));
		assertTrue(String.valueOf(ifMatch.get("description")).contains("重复"));
		assertTrue(String.valueOf(ifMatch.get("description")).contains("溢出"));
		assertTrue(String.valueOf(ifMatch.get("description")).contains("VERSION_CONFLICT"));
		Map<String, Object> idempotency = objectMap(parameters.get("IdempotencyKey"), "Idempotency-Key parameter");
		assertTrue(String.valueOf(idempotency.get("description")).contains("实际资源标识"));
		assertTrue(String.valueOf(idempotency.get("description")).contains("IDEMPOTENCY_KEY_REUSED"));
	}

	private static void assertRoute(Map<String, Object> paths, String operationId, String method, String path) {
		assertEquals(method + " " + path, ROUTES.get(operationId));
		Map<String, Object> operation = operation(paths, path, method.toLowerCase());
		assertEquals(operationId, operation.get("operationId"));
	}

	private static void assertRequestSchema(Map<String, Object> schema, Set<String> required) {
		assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
		assertEquals(required, newSet(schema.get("required")));
	}

	private static void assertTypeEnum(Map<String, Object> schema) {
		assertEquals(Set.of("FROZEN", "IN_TRANSIT", "RESERVED"),
			newSet(properties(schema).get("type"), "enum"));
	}

	private static void assertMoneyReferences(Map<String, Object> schema) {
		Map<String, Object> amount = objectMap(properties(schema).get("amount"), "amount schema");
		Map<String, Object> currency = objectMap(properties(schema).get("currency"), "currency schema");
		assertEquals("#/components/schemas/PositiveMoney", amount.get("$ref"));
		assertFalse("object".equals(amount.get("type")));
		assertEquals("#/components/schemas/Currency", currency.get("$ref"));
	}

	private static void assertIdempotency(Map<String, Object> operation, String requestHash) {
		Map<String, Object> idempotency = objectMap(operation.get("x-idempotency"), "LiquidityHold idempotency");
		assertEquals("CURRENT_USER_API_VERSION_OPERATION_ID_KEY", idempotency.get("scope"));
		assertEquals(requestHash, idempotency.get("requestHash"));
		assertEquals(List.of("VALIDATION_ERROR", "AUTHENTICATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND"),
			idempotency.get("rejectedBeforeRecord"));
	}

	private static void assertActiveReadWriteMatrix(Map<String, Object> operation) {
		Map<String, Object> permissions = objectMap(operation.get("x-permission-matrix"), "LiquidityHold permissions");
		assertEquals(List.of("OWNER", "EDITOR", "VIEWER"), permissions.get("read"));
		assertEquals(List.of("OWNER", "EDITOR"), permissions.get("write"));
		assertTrue(String.valueOf(operation.get("description")).contains("ACTIVE"));
	}

	private static void assertEnvelopeSchema(Map<String, Object> schemas, String name, boolean list) {
		Map<String, Object> envelope = schema(schemas, name);
		assertEquals(Boolean.FALSE, envelope.get("additionalProperties"));
		assertEquals(Set.of("data", "meta"), newSet(envelope.get("required")));
		Map<String, Object> data = objectMap(properties(envelope).get("data"), name + " data");
		if (list) {
			assertEquals("array", data.get("type"));
			assertEquals("#/components/schemas/LiquidityHold",
				objectMap(data.get("items"), name + " items").get("$ref"));
		}
		else {
			assertEquals("#/components/schemas/LiquidityHold", data.get("$ref"));
		}
	}

	private static void assertHasParameter(Map<String, Object> operation, String reference) {
		assertTrue(parameters(operation).stream().anyMatch(parameter -> reference.equals(parameter.get("$ref"))), reference);
	}

	private static Set<String> responseKeys(Map<String, Object> operation) {
		return objectMap(operation.get("responses"), "operation responses").keySet();
	}

	private static Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
		return objectMap(objectMap(paths.get(path), "OpenAPI path " + path).get(method), method + " " + path);
	}

	private static List<Map<String, Object>> parameters(Map<String, Object> operation) {
		Object value = operation.get("parameters");
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		return values.stream().map(valueItem -> objectMap(valueItem, "operation parameter")).toList();
	}

	private static Map<String, Object> schema(Map<String, Object> schemas, String name) {
		return objectMap(schemas.get(name), "schema " + name);
	}

	private static Map<String, Object> properties(Map<String, Object> schema) {
		return objectMap(schema.get("properties"), "schema properties");
	}

	private static Set<String> newSet(Object value) {
		if (!(value instanceof List<?> values)) {
			throw new IllegalStateException("契约字段必须是数组: " + value);
		}
		return values.stream().map(String.class::cast).collect(java.util.stream.Collectors.toSet());
	}

	private static Set<String> newSet(Object schemaValue, String field) {
		return newSet(objectMap(schemaValue, "schema field " + field).get(field));
	}

	private static Map<String, Object> readContract() throws IOException {
		Path contract = locateContract();
		try (InputStream input = Files.newInputStream(contract)) {
			return new Yaml().load(input);
		}
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
