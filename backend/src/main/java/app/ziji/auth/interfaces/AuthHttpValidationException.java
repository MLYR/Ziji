package app.ziji.auth.interfaces;

/** HTTP 传输字段、重复头或游标格式非法；不携带客户端输入内容。 */
final class AuthHttpValidationException extends RuntimeException {

	AuthHttpValidationException() {
		super("认证请求格式无效。");
	}
}
