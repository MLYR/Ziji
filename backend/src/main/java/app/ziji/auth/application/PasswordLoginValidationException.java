package app.ziji.auth.application;

/** 登录请求格式非法时的统一应用异常；在限流和 Argon2 校验之前抛出，不携带账号存在性信息。 */
public final class PasswordLoginValidationException extends RuntimeException {

	private static final String CODE = "VALIDATION_ERROR";

	public PasswordLoginValidationException() {
		super("登录请求格式无效。");
	}

	public String code() {
		return CODE;
	}
}
