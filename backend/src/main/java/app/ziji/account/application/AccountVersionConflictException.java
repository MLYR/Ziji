package app.ziji.account.application;

/** 版本条件更新未命中时携带当前可见账户，用于构造有界冲突详情。 */
public final class AccountVersionConflictException extends RuntimeException {

	private final AccountQueryResult current;

	public AccountVersionConflictException(AccountQueryResult current) {
		super("账户版本已变化。");
		if (current == null) {
			throw new IllegalArgumentException("当前账户不能为空。");
		}
		this.current = current;
	}

	public AccountQueryResult current() {
		return current;
	}
}
