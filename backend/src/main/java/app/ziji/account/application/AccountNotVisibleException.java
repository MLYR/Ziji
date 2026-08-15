package app.ziji.account.application;

/** 账户不存在或当前用户不可见；HTTP 边界统一映射为 404，不泄露资源存在性。 */
public final class AccountNotVisibleException extends RuntimeException {

	public AccountNotVisibleException() {
		super("账户不可见。");
	}
}
