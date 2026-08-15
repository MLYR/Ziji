package app.ziji.shared.application;

import java.util.UUID;
import java.util.regex.Pattern;

/** 已通过 HTTP 格式、认证、CSRF、权限和限流前置校验后的统一幂等请求。 */
public final class IdempotencyRequest {

	private static final Pattern REQUEST_HASH = Pattern.compile("[0-9a-f]{64}");

	private final IdempotencySubject subject;
	private final int apiMajorVersion;
	private final String operationId;
	private final String idempotencyKey;
	private final String requestHash;

	private IdempotencyRequest(
		IdempotencySubject subject,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash) {
		if (subject == null || apiMajorVersion < 1 || apiMajorVersion > Short.MAX_VALUE
			|| invalid(operationId, 1, 100) || invalid(idempotencyKey, 16, 100)
			|| requestHash == null || !REQUEST_HASH.matcher(requestHash).matches()) {
			throw new IdempotencyValidationException("幂等请求无效。");
		}
		this.subject = subject;
		this.apiMajorVersion = apiMajorVersion;
		this.operationId = operationId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	public static IdempotencyRequest authenticated(
		UUID userId,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash) {
		return new IdempotencyRequest(
			IdempotencySubject.authenticated(userId), apiMajorVersion, operationId, idempotencyKey, requestHash);
	}

	public static IdempotencyRequest anonymous(
		IdempotencySubject.Anonymous subject,
		int apiMajorVersion,
		String operationId,
		String idempotencyKey,
		String requestHash) {
		return new IdempotencyRequest(subject, apiMajorVersion, operationId, idempotencyKey, requestHash);
	}

	public IdempotencySubject subject() {
		return subject;
	}

	public int apiMajorVersion() {
		return apiMajorVersion;
	}

	public String operationId() {
		return operationId;
	}

	public String idempotencyKey() {
		return idempotencyKey;
	}

	public String requestHash() {
		return requestHash;
	}

	@Override
	public String toString() {
		return "IdempotencyRequest[redacted]";
	}

	private static boolean invalid(String value, int minimumLength, int maximumLength) {
		if (value == null || value.length() < minimumLength || value.length() > maximumLength) {
			return true;
		}
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) {
				return true;
			}
		}
		return false;
	}
}
