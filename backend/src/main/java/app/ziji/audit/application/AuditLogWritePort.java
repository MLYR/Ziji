package app.ziji.audit.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 业务模块追加审计事实的公开端口；调用方只能传递已脱敏的最小 metadata，不能传递完整请求体、reason 文本或凭据。
 */
public interface AuditLogWritePort {

	void append(AuditLogEntry entry);

	/**
	 * 与 audit_logs 一一对应的最小追加事实，不暴露数据库或框架类型；action、resourceType、reasonCode
	 * 使用大写下划线机器码，metadata 只接收非敏感的结构化键值。
	 */
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

		private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
		private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
		private static final Set<String> FORBIDDEN_METADATA_FRAGMENTS = Set.of(
			"password", "token", "authorization", "cookie", "secret", "credential", "privatekey", "apikey",
			"idempotencykey", "requestbody", "payload", "sql", "reason", "note", "sessionid", "deviceid",
			"verificationcode", "captcha", "otp");

		public AuditLogEntry {
			if (occurredAt == null || actorType == null || resourceId == null || result == null) {
				throw invalid();
			}
			if ((actorType == ActorType.USER && actorUserId == null)
				|| (actorType == ActorType.SYSTEM && actorUserId != null)) {
				throw invalid();
			}
			requireMachineCode(action, 80);
			requireMachineCode(resourceType, 50);
			requireText(requestId, 100);
			if (reasonCode != null) {
				requireMachineCode(reasonCode, 60);
			}
			metadata = normalizedMetadata(metadata);
		}

		private static Map<String, String> normalizedMetadata(Map<String, String> value) {
			if (value == null || value.size() > 16) {
				throw invalid();
			}
			Map<String, String> copy = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : value.entrySet()) {
				requireMetadataKey(entry.getKey());
				requireText(entry.getValue(), 160);
				copy.put(entry.getKey(), entry.getValue());
			}
			return Map.copyOf(copy);
		}

		private static void requireMachineCode(String value, int maximumLength) {
			requireText(value, maximumLength);
			if (!MACHINE_CODE.matcher(value).matches()) {
				throw invalid();
			}
		}

		private static void requireMetadataKey(String value) {
			requireText(value, 64);
			// 明显敏感字段必须使用专门安全字段或完全不进入审计 metadata。
			String normalized = value.replace("_", "").toLowerCase(Locale.ROOT);
			if (!METADATA_KEY.matcher(value).matches()
				|| FORBIDDEN_METADATA_FRAGMENTS.stream().anyMatch(normalized::contains)) {
				throw invalid();
			}
		}

		private static void requireText(String value, int maximumLength) {
			// 与 PostgreSQL varchar 和项目 OpenAPI 边界一致，按 Unicode code point 计数。
			if (value == null || value.isBlank()
				|| value.codePointCount(0, value.length()) > maximumLength) {
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
