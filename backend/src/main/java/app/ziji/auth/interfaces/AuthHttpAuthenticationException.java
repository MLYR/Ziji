package app.ziji.auth.interfaces;

/** 安全链未提供可信会话主体时的 fail-closed 信号，不解析任何客户端 sessionId。 */
final class AuthHttpAuthenticationException extends RuntimeException {

	AuthHttpAuthenticationException() {
		super("认证会话主体缺失。");
	}
}
