package app.ziji.shared.application;

/** 统一幂等处理后的类型化结果；HTTP 层仅需把状态映射到既有 Problem/重放语义。 */
public final class IdempotencyExecution<T> {

	public static final int RETRY_AFTER_SECONDS = 5;

	public enum Status {
		EXECUTED,
		REPLAYED,
		KEY_REUSED,
		REQUEST_IN_PROGRESS,
		SAFE_REPLAY_UNAVAILABLE
	}

	private final Status status;
	private final T value;
	private final IdempotencyResponse response;

	private IdempotencyExecution(Status status, T value, IdempotencyResponse response) {
		this.status = status;
		this.value = value;
		this.response = response;
	}

	static <T> IdempotencyExecution<T> executed(T value, IdempotencyResponse response) {
		return new IdempotencyExecution<>(Status.EXECUTED, value, response);
	}

	static <T> IdempotencyExecution<T> replayed(IdempotencyResponse response) {
		return new IdempotencyExecution<>(Status.REPLAYED, null, response);
	}

	static <T> IdempotencyExecution<T> keyReused() {
		return new IdempotencyExecution<>(Status.KEY_REUSED, null, null);
	}

	static <T> IdempotencyExecution<T> inProgress() {
		return new IdempotencyExecution<>(Status.REQUEST_IN_PROGRESS, null, null);
	}

	static <T> IdempotencyExecution<T> safeReplayUnavailable() {
		return new IdempotencyExecution<>(Status.SAFE_REPLAY_UNAVAILABLE, null, null);
	}

	public Status status() {
		return status;
	}

	public boolean executedNow() {
		return status == Status.EXECUTED;
	}

	public boolean replayed() {
		return status == Status.REPLAYED;
	}

	public T value() {
		return value;
	}

	public IdempotencyResponse response() {
		return response;
	}

	/** 处理中和可重试失败均使用 API/V009 冻结的固定 5 秒 Retry-After。 */
	public Integer retryAfterSeconds() {
		if (status == Status.REQUEST_IN_PROGRESS) {
			return RETRY_AFTER_SECONDS;
		}
		if (response != null && response.status() == IdempotencyResponse.Status.FAILED_RETRYABLE) {
			return RETRY_AFTER_SECONDS;
		}
		return null;
	}

	@Override
	public String toString() {
		return "IdempotencyExecution[status=" + status + "]";
	}
}
