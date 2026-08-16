package app.ziji.ledger.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 受冻结 Ledger payload 契约约束的最小 outbox 事实。 */
public record LedgerOutboxEvent(
	UUID eventId,
	UUID aggregateId,
	EventType eventType,
	Instant occurredAt,
	Map<String, Object> payload) {

	public static final String AGGREGATE_TYPE = "Transaction";

	public LedgerOutboxEvent {
		if (eventId == null || aggregateId == null || eventType == null || occurredAt == null || payload == null) {
			throw new LedgerCommandValidationException("Ledger outbox 事件无效。");
		}
		validatePayload(aggregateId, eventType, payload);
		payload = Map.copyOf(new LinkedHashMap<>(payload));
	}

	private static void validatePayload(UUID aggregateId, EventType eventType, Map<String, Object> payload) {
		// 只允许冻结的定位/版本字段，防止金额、分录、正文或凭据进入 outbox。
		Object operationKind = payload.get("operationKind");
		if (!(operationKind instanceof String operation)
			|| !Set.of("INITIAL", "REVISION", "VOID").contains(operation)) {
			throw new LedgerCommandValidationException("Ledger outbox 事件无效。");
		}
		Set<String> required = new HashSet<>();
		required.addAll(Set.of("schemaVersion", "transactionId", "rootTransactionId", "entityVersion", "operationKind"));
		if (eventType == EventType.TransactionPosted) {
			required.add("versionNo");
			if (operation.equals("INITIAL")) {
				validateKeys(payload, required);
			} else if (operation.equals("REVISION")) {
				required.addAll(Set.of("replacementTransactionId", "replacementVersionNo", "replacementEntityVersion"));
				validateKeys(payload, required);
			} else {
				throw new LedgerCommandValidationException("Ledger outbox 事件无效。");
			}
		} else {
			required.addAll(Set.of(
				"reversalOfTransactionId", "reversalOfVersionNo", "reversalOfEntityVersionBefore",
				"reversalOfEntityVersionAfter"));
			if (operation.equals("VOID")) {
				validateKeys(payload, required);
			} else if (operation.equals("REVISION")) {
				required.addAll(Set.of("replacementTransactionId", "replacementVersionNo", "replacementEntityVersion"));
				validateKeys(payload, required);
			} else {
				throw new LedgerCommandValidationException("Ledger outbox 事件无效。");
			}
		}
		validatePositiveInteger(payload, "schemaVersion");
		if (!Integer.valueOf(1).equals(payload.get("schemaVersion"))) {
			throw new LedgerCommandValidationException("Ledger outbox schema 版本无效。");
		}
		validatePositiveInteger(payload, "entityVersion");
		if (eventType == EventType.TransactionPosted) {
			validatePositiveInteger(payload, "versionNo");
		}
		validateUuid(payload, "transactionId");
		validateUuid(payload, "rootTransactionId");
		if (!aggregateId.equals(UUID.fromString((String) payload.get("transactionId")))) {
			throw new LedgerCommandValidationException("Ledger outbox 聚合 ID 与载荷不一致。");
		}
		if (eventType == EventType.TransactionReversed) {
			validateUuid(payload, "reversalOfTransactionId");
			validatePositiveInteger(payload, "reversalOfVersionNo");
			validatePositiveInteger(payload, "reversalOfEntityVersionBefore");
			validatePositiveInteger(payload, "reversalOfEntityVersionAfter");
			if (((Integer) payload.get("reversalOfEntityVersionBefore")) + 1
				!= (Integer) payload.get("reversalOfEntityVersionAfter")) {
				throw new LedgerCommandValidationException("Ledger outbox 原交易版本关系无效。");
			}
		}
		if (operation.equals("REVISION")) {
			validateUuid(payload, "replacementTransactionId");
			validatePositiveInteger(payload, "replacementVersionNo");
			validatePositiveInteger(payload, "replacementEntityVersion");
		}
	}

	private static void validateKeys(Map<String, Object> payload, Set<String> required) {
		if (!payload.keySet().equals(required) || required.stream().anyMatch(key -> payload.get(key) == null)) {
			throw new LedgerCommandValidationException("Ledger outbox 载荷字段无效。");
		}
	}

	private static void validatePositiveInteger(Map<String, Object> payload, String key) {
		if (!(payload.get(key) instanceof Integer value) || value <= 0) {
			throw new LedgerCommandValidationException("Ledger outbox 版本字段无效。");
		}
	}

	private static void validateUuid(Map<String, Object> payload, String key) {
		if (!(payload.get(key) instanceof String value)) {
			throw new LedgerCommandValidationException("Ledger outbox ID 字段无效。");
		}
		try {
			UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new LedgerCommandValidationException("Ledger outbox ID 字段无效。");
		}
	}

	public enum EventType {
		TransactionPosted,
		TransactionReversed
	}
}
