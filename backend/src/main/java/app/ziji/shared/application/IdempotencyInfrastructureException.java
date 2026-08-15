package app.ziji.shared.application;

/** 幂等基础设施的非业务失败；消息不携带 SQL、Key、Hash、主体或响应内容。 */
public final class IdempotencyInfrastructureException extends RuntimeException {

	public IdempotencyInfrastructureException(String message, Throwable cause) {
		super(message, cause);
	}

	public IdempotencyInfrastructureException(String message) {
		super(message);
	}
}
