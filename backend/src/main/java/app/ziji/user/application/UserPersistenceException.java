package app.ziji.user.application;

/** 用户资料存储失败；响应层不暴露底层 SQL 或驱动异常。 */
public final class UserPersistenceException extends RuntimeException {

	public UserPersistenceException(Throwable cause) {
		super("用户资料存储失败。", cause);
	}
}
