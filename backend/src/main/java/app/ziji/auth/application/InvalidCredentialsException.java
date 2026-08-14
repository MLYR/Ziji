package app.ziji.auth.application;

/**
 * 凭据认证失败的统一应用异常；LOCKED、CLOSED、用户不存在和密码错误都映射到同一 code 与消息，
 * 不携带 email、userId、status、passwordHashVersion 或内部异常，避免账号枚举。
 */
public final class InvalidCredentialsException extends RuntimeException {

	private static final String CODE = "INVALID_CREDENTIALS";

	public InvalidCredentialsException() {
		super("邮箱或密码无效。");
	}

	public String code() {
		return CODE;
	}
}
