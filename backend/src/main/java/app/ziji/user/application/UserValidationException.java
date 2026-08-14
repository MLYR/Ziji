package app.ziji.user.application;

/** 用户资料接口输入不符合契约时使用的安全异常。 */
public final class UserValidationException extends RuntimeException {

	public UserValidationException(String message) {
		super(message);
	}
}
