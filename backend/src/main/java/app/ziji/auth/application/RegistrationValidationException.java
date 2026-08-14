package app.ziji.auth.application;

/** 注册资料或验证码无效时的统一应用异常，不暴露挑战内部状态。 */
public final class RegistrationValidationException extends RuntimeException {

	public RegistrationValidationException() {
		super("注册资料或验证码无效。");
	}
}
