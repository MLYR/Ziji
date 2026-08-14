package app.ziji.user.domain;

/** 用户资料领域不变量失败时使用的明确异常，不把底层异常暴露给接口层。 */
public final class UserDomainException extends IllegalArgumentException {

	public UserDomainException(String message) {
		super(message);
	}
}
