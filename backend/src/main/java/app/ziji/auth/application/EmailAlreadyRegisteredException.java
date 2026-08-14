package app.ziji.auth.application;

/** 邮箱唯一约束冲突的明确注册异常；底层 SQL 和约束细节不向调用方泄漏。 */
public final class EmailAlreadyRegisteredException extends RuntimeException {

	public EmailAlreadyRegisteredException() {
		super("邮箱已注册。");
	}
}
