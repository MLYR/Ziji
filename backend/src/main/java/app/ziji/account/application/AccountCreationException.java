package app.ziji.account.application;

/** 账户创建命令在应用边界的可理解校验失败。 */
public final class AccountCreationException extends IllegalArgumentException {

	public AccountCreationException(String message) {
		super(message);
	}
}
