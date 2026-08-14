package app.ziji.auth.application;

/** 会话创建输入无效时的稳定应用异常；不包含设备原值或任何凭据。 */
public final class SessionTokenValidationException extends IllegalArgumentException {

	public SessionTokenValidationException() {
		super("会话请求无效。");
	}
}
