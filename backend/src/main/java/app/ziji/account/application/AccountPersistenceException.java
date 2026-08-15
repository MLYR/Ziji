package app.ziji.account.application;

/** 账户存储失败；响应和日志不得暴露 SQL、参数或驱动消息。 */
public final class AccountPersistenceException extends RuntimeException {

	public AccountPersistenceException(Throwable cause) {
		super("账户存储失败。", cause);
	}
}
