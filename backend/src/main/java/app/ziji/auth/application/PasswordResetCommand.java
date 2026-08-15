package app.ziji.auth.application;

/** 密码重置输入；验证码和新密码只在调用栈短暂存在，不提供可记录的 toString。 */
public final class PasswordResetCommand {

	private final String email;
	private final String verificationCode;
	private final String newPassword;

	public PasswordResetCommand(String email, String verificationCode, String newPassword) {
		this.email = email;
		this.verificationCode = verificationCode;
		this.newPassword = newPassword;
	}

	public String email() {
		return email;
	}

	public String verificationCode() {
		return verificationCode;
	}

	public String newPassword() {
		return newPassword;
	}
}
