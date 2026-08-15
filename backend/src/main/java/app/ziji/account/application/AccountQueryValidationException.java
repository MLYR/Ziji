package app.ziji.account.application;

/** 账户查询/更新参数非法时的安全校验错误，不携带客户端输入内容。 */
public final class AccountQueryValidationException extends RuntimeException {

	public AccountQueryValidationException() {
		super("账户查询参数无效。");
	}

	public AccountQueryValidationException(String message) {
		super(message);
	}
}
