package app.ziji.auth.application;

/** 密码重置或输入校验失败；不携带邮箱、验证码、明文密码或 Hash。 */
public final class PasswordManagementValidationException extends RuntimeException {

	public PasswordManagementValidationException() {
		super("密码操作请求无效。");
	}

	public String code() {
		return "VALIDATION_ERROR";
	}
}
