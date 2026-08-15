package app.ziji.shared.application;

import java.util.UUID;
import java.util.regex.Pattern;

/** V009 允许持久化和安全重放的最小响应引用，不承载完整 HTTP 响应。 */
public final class IdempotencyResponse {

	private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

	public enum Status {
		SUCCEEDED,
		FAILED_FINAL,
		FAILED_RETRYABLE
	}

	private final Status status;
	private final int responseStatus;
	private final Reference reference;
	private final String resourceType;
	private final UUID resourceId;

	private IdempotencyResponse(
		Status status,
		int responseStatus,
		Reference reference,
		String resourceType,
		UUID resourceId) {
		if (status == null || reference == null || (resourceType == null) != (resourceId == null)) {
			throw invalid();
		}
		if (status == Status.SUCCEEDED && (responseStatus < 200 || responseStatus > 299)) {
			throw invalid();
		}
		if (status == Status.FAILED_FINAL && (responseStatus < 400 || responseStatus > 499)) {
			throw invalid();
		}
		if (status == Status.FAILED_RETRYABLE && (responseStatus < 500 || responseStatus > 599)) {
			throw invalid();
		}
		if (reference instanceof EmptyReference && (status != Status.SUCCEEDED || resourceType != null)) {
			throw invalid();
		}
		if (reference instanceof ResourceReference && (status != Status.SUCCEEDED || invalidResourceType(resourceType))) {
			throw invalid();
		}
		if (reference instanceof ProblemReference problem
			&& (status == Status.SUCCEEDED || problem.retryable() != (status == Status.FAILED_RETRYABLE)
				|| resourceType != null)) {
			throw invalid();
		}
		this.status = status;
		this.responseStatus = responseStatus;
		this.reference = reference;
		this.resourceType = resourceType;
		this.resourceId = resourceId;
	}

	public static IdempotencyResponse succeededEmpty(int responseStatus) {
		return new IdempotencyResponse(Status.SUCCEEDED, responseStatus, EmptyReference.INSTANCE, null, null);
	}

	public static IdempotencyResponse succeededResource(
		int responseStatus,
		String resourceType,
		UUID resourceId,
		ResourceReference reference) {
		return new IdempotencyResponse(Status.SUCCEEDED, responseStatus, reference, resourceType, resourceId);
	}

	public static IdempotencyResponse failedFinal(int responseStatus, String errorCode) {
		return new IdempotencyResponse(
			Status.FAILED_FINAL, responseStatus, new ProblemReference(errorCode, false), null, null);
	}

	/** 调用方只有确认没有业务事实、审计或 outbox 时才能使用可重试终态。 */
	public static IdempotencyResponse failedRetryable(int responseStatus, String errorCode) {
		return new IdempotencyResponse(
			Status.FAILED_RETRYABLE, responseStatus, new ProblemReference(errorCode, true), null, null);
	}

	public Status status() {
		return status;
	}

	public int responseStatus() {
		return responseStatus;
	}

	public Reference reference() {
		return reference;
	}

	public String resourceType() {
		return resourceType;
	}

	public UUID resourceId() {
		return resourceId;
	}

	@Override
	public String toString() {
		return "IdempotencyResponse[status=" + status + ", responseStatus=" + responseStatus + "]";
	}

	public sealed interface Reference permits EmptyReference, ResourceReference, ProblemReference {

		String kind();
	}

	public static final class EmptyReference implements Reference {

		private static final EmptyReference INSTANCE = new EmptyReference();

		private EmptyReference() {
		}

		public static EmptyReference instance() {
			return INSTANCE;
		}

		@Override
		public String kind() {
			return "EMPTY";
		}
	}

	public static final class ResourceReference implements Reference {

		private final String location;
		private final String etag;
		private final Long resourceVersion;

		public ResourceReference(String location, String etag, Long resourceVersion) {
			if (invalidLocation(location) || invalidEtag(etag)
				|| resourceVersion != null && (resourceVersion < 1 || resourceVersion > 9_999_999_999L)) {
				throw invalid();
			}
			this.location = location;
			this.etag = etag;
			this.resourceVersion = resourceVersion;
		}

		@Override
		public String kind() {
			return "RESOURCE";
		}

		public String location() {
			return location;
		}

		public String etag() {
			return etag;
		}

		public Long resourceVersion() {
			return resourceVersion;
		}
	}

	public static final class ProblemReference implements Reference {

		private final String errorCode;
		private final boolean retryable;

		private ProblemReference(String errorCode, boolean retryable) {
			if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
				throw invalid();
			}
			this.errorCode = errorCode;
			this.retryable = retryable;
		}

		@Override
		public String kind() {
			return "PROBLEM";
		}

		public String errorCode() {
			return errorCode;
		}

		public boolean retryable() {
			return retryable;
		}
	}

	private static boolean invalidResourceType(String value) {
		return value == null || value.isBlank() || value.length() > 50 || containsControl(value);
	}

	private static boolean invalidLocation(String value) {
		return value != null && (value.length() > 512 || !value.startsWith("/") || value.startsWith("//")
			|| containsControl(value));
	}

	private static boolean invalidEtag(String value) {
		return value != null && (value.isBlank() || value.length() > 80 || containsControl(value));
	}

	private static boolean containsControl(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) {
				return true;
			}
		}
		return false;
	}

	private static IdempotencyValidationException invalid() {
		return new IdempotencyValidationException("幂等安全响应引用无效。");
	}
}
