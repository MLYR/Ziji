package app.ziji.user.application;

/** 无法从已认证安全主体得到用户 UUID 时使用的明确异常。 */
public final class UserAuthenticationException extends RuntimeException {

	public UserAuthenticationException() {
		super("当前用户认证信息无效。");
	}
}
