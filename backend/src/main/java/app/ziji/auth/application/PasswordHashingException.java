package app.ziji.auth.application;

/** 密码 Hash 基础设施失败的明确异常；消息中不得携带原始密码。 */
public final class PasswordHashingException extends RuntimeException {

	public PasswordHashingException(Throwable cause) {
		super("密码安全处理失败。", cause);
	}
}
