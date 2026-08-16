package app.ziji.audit.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 业务模块追加审计事实的公开端口；调用方只能传递已脱敏的最小 metadata，不能传递完整请求体、reason 文本或凭据。
 */
public interface AuditLogWritePort {

	void append(AuditLogEntry entry);

	/** 与 audit_logs 一一对应的最小追加事实，不暴露数据库或框架类型。 */
	record AuditLogEntry(
		Instant occurredAt,
		UUID actorUserId,
		ActorType actorType,
		String action,
		String resourceType,
		UUID resourceId,
		UUID accountId,
		String requestId,
		Result result,
		String reasonCode,
		Map<String, String> metadata) {

		public AuditLogEntry {
			if (occurredAt == null || actorType == null || resourceId == null || result == null) {
				throw invalid();
			}
			if ((actorType == ActorType.USER && actorUserId == null)
				|| (actorType == ActorType.SYSTEM && actorUserId != null)) {
				throw invalid();
			}
			requireText(action, 80);
			requireText(resourceType, 50);
			requireText(requestId, 100);
			if (reasonCode != null) {
				requireText(reasonCode, 60);
			}
			metadata = normalizedMetadata(metadata);
		}

		private static Map<String, String> normalizedMetadata(Map<String, String> value) {
			if (value == null || value.size() > 16) {
				throw invalid();
			}
			Map<String, String> copy = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : value.entrySet()) {
				requireText(entry.getKey(), 64);
				requireText(entry.getValue(), 160);
				copy.put(entry.getKey(), entry.getValue());
			}
			return Map.copyOf(copy);
		}

		private static void requireText(String value, int maximumLength) {
			if (value == null || value.isBlank() || value.length() > maximumLength) {
				throw invalid();
			}
			for (int index = 0; index < value.length(); index++) {
				if (Character.isISOControl(value.charAt(index))) {
					throw invalid();
				}
			}
		}

		private static IllegalArgumentException invalid() {
			return new IllegalArgumentException("审计追加事实无效。");
		}
	}

	enum ActorType {
		USER,
		SYSTEM
	}

	enum Result {
		SUCCESS,
		DENIED,
		FAILED
	}
}
