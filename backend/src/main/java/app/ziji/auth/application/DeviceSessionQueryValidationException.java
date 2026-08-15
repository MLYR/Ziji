package app.ziji.auth.application;

/** 会话列表 limit/cursor 非法时的安全校验错误，不暴露其他用户会话是否存在。 */
public final class DeviceSessionQueryValidationException extends RuntimeException {

	public DeviceSessionQueryValidationException() {
		super("设备会话查询参数无效。");
	}
}
