package app.ziji.user.application;

/** users.email_normalized 唯一约束冲突的明确应用异常，不混淆其他数据库失败。 */
public final class UserEmailAlreadyExistsException extends RuntimeException {

	public UserEmailAlreadyExistsException() {
		super("邮箱已存在。");
	}
}
