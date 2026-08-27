package app.ziji.account.application;

/** 余额事实读取或共同快照无法安全完成时的内部失败；HTTP 层统一隐藏底层原因。 */
public final class AccountBalanceException extends RuntimeException {

	private AccountBalanceException(String message, Throwable cause) {
		super(message, cause);
	}

	public static AccountBalanceException persistence(Throwable cause) {
		return new AccountBalanceException("账户余额事实读取失败。", cause);
	}
}
