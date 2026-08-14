package app.ziji.auth.domain;

/** 认证领域不变量或输入校验失败时使用的明确异常。 */
public final class AuthDomainException extends IllegalArgumentException {

	public AuthDomainException(String message) {
		super(message);
	}
}
