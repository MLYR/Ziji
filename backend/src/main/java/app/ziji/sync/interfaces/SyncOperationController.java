package app.ziji.sync.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.ziji.ledger.application.SyncLedgerCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.sync.application.SyncOperationApplicationService;
import app.ziji.sync.application.SyncQueryValidationException;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Sync 上传仅解析冻结的语义联合体；账务权限、科目、分录和事实写入均留在 Ledger。 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncOperationController {

	private static final Set<String> OPERATION_FIELDS = Set.of(
		"operationId", "idempotencyKey", "entityType", "entityId", "operationType", "baseVersion", "payloadVersion", "payload", "createdAt");

	private final SyncOperationApplicationService operations;
	private final CurrentUserIdResolver currentUserIdResolver;

	public SyncOperationController(
		SyncOperationApplicationService operations, CurrentUserIdResolver currentUserIdResolver) {
		this.operations = operations;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@PostMapping(path = "/operations", consumes = MediaType.APPLICATION_JSON_VALUE, name = "applySyncOperations")
	public ResponseEntity<SyncOperationResultsEnvelope> apply(
		@RequestBody JsonNode body, java.security.Principal principal, HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		List<JsonNode> rawOperations = requestOperations(body);
		String requestId = requestId(response);
		List<Map<String, Object>> results = new ArrayList<>(rawOperations.size());
		for (JsonNode raw : rawOperations) {
			UUID operationId;
			try {
				operationId = requiredUuid(raw, "operationId");
			} catch (OperationRejectedException exception) {
				// 无法关联结果的项不能伪造 operationId，整包按 OpenAPI Problem 拒绝。
				throw new SyncQueryValidationException();
			}
			try {
				SyncOperationApplicationService.Operation operation = parseOperation(raw, userId);
				results.add(view(operations.apply(userId, operation, requestId)));
			} catch (OperationRejectedException exception) {
				results.add(rejected(operationId, exception.code(), requestId));
			}
		}
		return ResponseEntity.ok(new SyncOperationResultsEnvelope(new ResultsData(results), new ResponseMeta(requestId)));
	}

	private List<JsonNode> requestOperations(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != 2 || !body.has("deviceId") || !body.has("operations")
			|| !body.get("deviceId").isTextual() || body.get("deviceId").textValue().isBlank()
			|| body.get("deviceId").textValue().length() > 200 || !body.get("operations").isArray()) {
			throw new SyncQueryValidationException();
		}
		List<JsonNode> values = new ArrayList<>();
		for (int index = 0; index < body.get("operations").size(); index++) values.add(body.get("operations").get(index));
		if (values.isEmpty() || values.size() > 100) throw new SyncQueryValidationException();
		return values;
	}

	private SyncOperationApplicationService.Operation parseOperation(JsonNode node, UUID userId) {
		if (node == null || !node.isObject() || node.size() != OPERATION_FIELDS.size()) reject("VALIDATION_ERROR");
		for (String field : OPERATION_FIELDS) if (!node.has(field)) reject("VALIDATION_ERROR");
		for (String name : node.propertyNames()) if (!OPERATION_FIELDS.contains(name)) reject("VALIDATION_ERROR");
		UUID operationId = requiredUuid(node, "operationId");
		String key = text(node, "idempotencyKey");
		if (key.length() < 16 || key.length() > 100) reject("VALIDATION_ERROR");
		if (!"TRANSACTION".equals(text(node, "entityType"))) reject("VALIDATION_ERROR");
		UUID entityId = requiredUuid(node, "entityId");
		if (!node.get("payloadVersion").isInt() || node.get("payloadVersion").intValue() != 1) reject("VALIDATION_ERROR");
		if (!node.get("createdAt").isTextual()) reject("VALIDATION_ERROR");
		try { OffsetDateTime.parse(node.get("createdAt").textValue()); } catch (RuntimeException exception) { reject("VALIDATION_ERROR"); }
		String operationType = text(node, "operationType");
		JsonNode payload = node.get("payload");
		if (!payload.isObject()) reject("VALIDATION_ERROR");
		Integer baseVersion;
		SyncLedgerCommand command;
		switch (operationType) {
			case "CREATE" -> {
				if (!node.get("baseVersion").isNull()) reject("VALIDATION_ERROR");
				baseVersion = null;
				command = parseCreate(userId, entityId, payload);
			}
			case "UPDATE" -> {
				baseVersion = positiveVersion(node.get("baseVersion"));
				ensureFields(payload, Set.of("reason", "replacement"));
				String reason = bounded(text(payload, "reason"), 500);
				command = new SyncLedgerCommand.Revision(userId, entityId, baseVersion, reason,
					parseReplacement(object(payload, "replacement")));
			}
			case "REVERSE" -> {
				baseVersion = positiveVersion(node.get("baseVersion"));
				ensureFields(payload, Set.of("reason"));
				command = new SyncLedgerCommand.Reverse(userId, entityId, baseVersion, bounded(text(payload, "reason"), 500));
			}
			default -> throw new OperationRejectedException("VALIDATION_ERROR");
		}
		Map<String, Object> hashPayload = new LinkedHashMap<>();
		hashPayload.put("operationId", operationId);
		hashPayload.put("entityType", "TRANSACTION");
		hashPayload.put("operationType", operationType);
		hashPayload.put("entityId", entityId);
		hashPayload.put("baseVersion", baseVersion);
		hashPayload.put("payloadVersion", 1);
		hashPayload.put("payload", canonical(payload));
		return new SyncOperationApplicationService.Operation(operationId, key, entityId, baseVersion, 1, command, hashPayload);
	}

	private SyncLedgerCommand parseCreate(UUID userId, UUID transactionId, JsonNode payload) {
		String type = text(payload, "type");
		return switch (type) {
			case "INCOME" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "categoryId", "counterparty"));
				yield new SyncLedgerCommand.Income(userId, transactionId, requiredUuid(payload, "accountId"), requiredUuid(payload, "categoryId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload), nullableText(payload, "counterparty", 200), nullableText(payload, "note", 2000));
			}
			case "EXPENSE" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "categoryId", "merchant"));
				yield new SyncLedgerCommand.Expense(userId, transactionId, requiredUuid(payload, "accountId"), requiredUuid(payload, "categoryId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload), nullableText(payload, "merchant", 200), nullableText(payload, "note", 2000));
			}
			case "REFUND" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "originalTransactionId"));
				yield new SyncLedgerCommand.Refund(userId, transactionId, requiredUuid(payload, "accountId"), requiredUuid(payload, "originalTransactionId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload), nullableText(payload, "note", 2000));
			}
			case "TRANSFER" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "fromAccountId", "toAccountId", "fromAmount", "toAmount", "fee", "feeCategoryId"));
				Money from = moneyObject(payload, "fromAmount", true);
				Money to = moneyObject(payload, "toAmount", true);
				Money fee = moneyObject(payload, "fee", false);
				UUID feeCategory = nullableUuidField(payload, "feeCategoryId");
				if (from.currency() != to.currency() || from.currency() != fee.currency() || from.amount().compareTo(to.amount()) != 0
					|| fee.amount().signum() == 0 && feeCategory != null || fee.amount().signum() > 0 && feeCategory == null) reject("BUSINESS_RULE_VIOLATION");
				yield new SyncLedgerCommand.Transfer(userId, transactionId, requiredUuid(payload, "fromAccountId"), requiredUuid(payload, "toAccountId"), feeCategory,
					from, fee, instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload), nullableText(payload, "note", 2000));
			}
			default -> throw new OperationRejectedException("VALIDATION_ERROR");
		};
	}

	private SyncLedgerCommand.Replacement parseReplacement(JsonNode payload) {
		String type = text(payload, "type");
		return switch (type) {
			case "INCOME" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "categoryId", "counterparty"));
				yield new SyncLedgerCommand.Replacement.Income(requiredUuid(payload, "accountId"), requiredUuid(payload, "categoryId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload),
					nullableText(payload, "counterparty", 200), nullableText(payload, "note", 2000));
			}
			case "EXPENSE" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "categoryId", "merchant"));
				yield new SyncLedgerCommand.Replacement.Expense(requiredUuid(payload, "accountId"), requiredUuid(payload, "categoryId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload),
					nullableText(payload, "merchant", 200), nullableText(payload, "note", 2000));
			}
			case "REFUND" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "accountId", "amount", "currency", "originalTransactionId"));
				yield new SyncLedgerCommand.Replacement.Refund(requiredUuid(payload, "accountId"), requiredUuid(payload, "originalTransactionId"),
					money(payload, "amount", "currency"), instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload),
					nullableText(payload, "note", 2000));
			}
			case "TRANSFER" -> {
				ensureFields(payload, Set.of("type", "businessAt", "businessDate", "timezone", "note", "fromAccountId", "toAccountId", "fromAmount", "toAmount", "fee", "feeCategoryId"));
				Money from = moneyObject(payload, "fromAmount", true);
				Money to = moneyObject(payload, "toAmount", true);
				Money fee = moneyObject(payload, "fee", false);
				UUID feeCategory = nullableUuidField(payload, "feeCategoryId");
				if (from.currency() != to.currency() || from.currency() != fee.currency() || from.amount().compareTo(to.amount()) != 0
					|| fee.amount().signum() == 0 && feeCategory != null || fee.amount().signum() > 0 && feeCategory == null) reject("BUSINESS_RULE_VIOLATION");
				yield new SyncLedgerCommand.Replacement.Transfer(requiredUuid(payload, "fromAccountId"), requiredUuid(payload, "toAccountId"), feeCategory,
					from, fee, instant(payload, "businessAt"), date(payload, "businessDate"), timezone(payload), nullableText(payload, "note", 2000));
			}
			default -> throw new OperationRejectedException("VALIDATION_ERROR");
		};
	}

	private static Map<String, Object> canonical(JsonNode node) {
		Map<String, Object> value = new LinkedHashMap<>();
		for (String field : node.propertyNames()) {
			JsonNode child = node.get(field);
			if (child.isObject()) value.put(field, canonical(child));
			else if (child.isNull()) value.put(field, null);
			else if ("amount".equals(field)) value.put(field, IdempotencyRequestHasher.decimal(child.textValue()));
			else if (field.endsWith("Id")) value.put(field, UUID.fromString(child.textValue()));
			else if ("businessAt".equals(field)) value.put(field, OffsetDateTime.parse(child.textValue()).toInstant());
			else if ("businessDate".equals(field)) value.put(field, LocalDate.parse(child.textValue()));
			else value.put(field, child.textValue());
		}
		return value;
	}

	private static void ensureFields(JsonNode node, Set<String> allowed) {
		if (node == null || !node.isObject()) reject("VALIDATION_ERROR");
		for (String name : node.propertyNames()) if (!allowed.contains(name)) reject("VALIDATION_ERROR");
	}
	private static JsonNode object(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isObject()) reject("VALIDATION_ERROR"); return value; }
	private static String text(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isTextual() || value.textValue().isBlank()) reject("VALIDATION_ERROR"); return value.textValue(); }
	private static String bounded(String value, int max) { if (value.codePointCount(0, value.length()) > max) reject("VALIDATION_ERROR"); return value; }
	private static String nullableText(JsonNode node, String field, int max) { JsonNode value = node.get(field); if (value == null || value.isNull()) return null; if (!value.isTextual()) reject("VALIDATION_ERROR"); return bounded(value.textValue(), max); }
	private static UUID requiredUuid(JsonNode node, String field) { try { return UUID.fromString(text(node, field)); } catch (RuntimeException exception) { throw new OperationRejectedException("VALIDATION_ERROR"); } }
	/** OpenAPI required 的可空 UUID 必须显式出现；缺失不能被降级为业务 null。 */
	private static UUID nullableUuidField(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null) reject("VALIDATION_ERROR");
		return value.isNull() ? null : requiredUuid(node, field);
	}
	private static Integer positiveVersion(JsonNode node) { if (node == null || !node.isInt() || node.intValue() <= 0) reject("VALIDATION_ERROR"); return node.intValue(); }
	private static Instant instant(JsonNode node, String field) { try { return OffsetDateTime.parse(text(node, field)).toInstant(); } catch (RuntimeException exception) { throw new OperationRejectedException("VALIDATION_ERROR"); } }
	private static LocalDate date(JsonNode node, String field) { try { return LocalDate.parse(text(node, field)); } catch (RuntimeException exception) { throw new OperationRejectedException("VALIDATION_ERROR"); } }
	private static String timezone(JsonNode node) { String value = bounded(text(node, "timezone"), 64); try { ZoneId.of(value); return value; } catch (RuntimeException exception) { throw new OperationRejectedException("VALIDATION_ERROR"); } }
	private static Money money(JsonNode node, String amountField, String currencyField) { try { return new Money(new BigDecimal(text(node, amountField)), CurrencyCode.fromCode(text(node, currencyField))); } catch (RuntimeException exception) { throw new OperationRejectedException("VALIDATION_ERROR"); } }
	private static Money moneyObject(JsonNode node, String field, boolean positive) { JsonNode value = object(node, field); ensureFields(value, Set.of("amount", "currency")); Money money = money(value, "amount", "currency"); if (positive ? money.amount().signum() <= 0 : money.amount().signum() < 0) reject("VALIDATION_ERROR"); return money; }
	private static void reject(String code) { throw new OperationRejectedException(code); }

	private static Map<String, Object> rejected(UUID operationId, String code, String requestId) {
		Map<String, Object> error = new LinkedHashMap<>(); error.put("type", "https://ziji.app/problems/" + code.toLowerCase().replace('_', '-'));
		error.put("title", "Rejected"); error.put("status", 422); error.put("code", code); error.put("requestId", requestId);
		return Map.of("operationId", operationId, "status", "REJECTED", "error", error);
	}
	private static Map<String, Object> view(SyncOperationApplicationService.Result result) {
		Map<String, Object> value = new LinkedHashMap<>(); value.put("operationId", result.operationId()); value.put("status", result.status());
		if (result.entityId() != null) { value.put("entityId", result.entityId()); value.put("entityVersion", result.entityVersion()); }
		if (result.error() != null) value.put("error", result.error()); if (result.retryAfterSeconds() != null) value.put("retryAfterSeconds", result.retryAfterSeconds()); return value;
	}
	private static String requestId(HttpServletResponse response) { String value = response.getHeader("X-Request-ID"); return value == null || value.isBlank() ? "unknown" : value; }

	public record SyncOperationResultsEnvelope(ResultsData data, ResponseMeta meta) { }
	public record ResultsData(List<Map<String, Object>> results) { public ResultsData { results = List.copyOf(results); } }
	public record ResponseMeta(String requestId) { }
	private static final class OperationRejectedException extends RuntimeException { private final String code; private OperationRejectedException(String code) { this.code = code; } private String code() { return code; } }
}
