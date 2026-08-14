package app.ziji.auth.application;

import java.util.UUID;

import app.ziji.user.application.UserCredentialStatus;

/**
 * 密码登录安全认证结果；只暴露已认证用户 ID 和凭据状态，供后续稳定设备会话用例使用。
 * 严禁包含 password、passwordHash、accessToken、refreshToken、Cookie 或 sessionId。
 */
public record PasswordLoginResult(
	UUID userId,
	UserCredentialStatus status) {
}
