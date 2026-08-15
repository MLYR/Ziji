package app.ziji.account.domain;

/** 账户领域不变量失败时使用的明确异常，不把底层存储错误暴露给调用方。 */
public final class AccountDomainException extends IllegalArgumentException {

	public AccountDomainException(String message) {
		super(message);
	}
}
