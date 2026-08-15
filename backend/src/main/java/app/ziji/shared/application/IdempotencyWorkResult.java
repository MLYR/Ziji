package app.ziji.shared.application;

/** 业务工作完成后的安全终态和可选业务结果；响应引用永不保存完整响应体。 */
public final class IdempotencyWorkResult<T> {

	private final T value;
	private final IdempotencyResponse response;

	private IdempotencyWorkResult(T value, IdempotencyResponse response) {
		if (response == null) {
			throw new IdempotencyValidationException("幂等业务终态不能为空。");
		}
		this.value = value;
		this.response = response;
	}

	public static <T> IdempotencyWorkResult<T> completed(T value, IdempotencyResponse response) {
		return new IdempotencyWorkResult<>(value, response);
	}

	public T value() {
		return value;
	}

	public IdempotencyResponse response() {
		return response;
	}

	@Override
	public String toString() {
		return "IdempotencyWorkResult[response=" + response.status() + "]";
	}
}
