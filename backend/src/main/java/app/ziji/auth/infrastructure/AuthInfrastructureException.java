package app.ziji.auth.infrastructure;

/** 认证基础设施失败的内部异常；消息不包含密钥、验证码或底层 SQL 细节。 */
public final class AuthInfrastructureException extends RuntimeException {

	public AuthInfrastructureException(String message) {
		super(message);
	}

	public AuthInfrastructureException(String message, Throwable cause) {
		super(message, cause);
	}
}
