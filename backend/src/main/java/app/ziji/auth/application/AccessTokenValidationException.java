package app.ziji.auth.application;

/** Access Token 校验失败的稳定异常；不回显签名、Header、Claims 或密钥信息。 */
public final class AccessTokenValidationException extends RuntimeException {

	public AccessTokenValidationException() {
		super("Access Token 无效。");
	}
}
