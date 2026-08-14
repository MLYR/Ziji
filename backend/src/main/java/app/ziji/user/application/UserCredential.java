package app.ziji.user.application;

import java.util.UUID;

/**
 * 认证所需的最小用户凭据视图；只包含安全认证字段，不得携带资料、账务数据或 Token。
 * auth application 只依赖本公开 application DTO，不接触 user domain 或 infrastructure 类型。
 */
public record UserCredential(
	UUID userId,
	String passwordHash,
	int passwordHashVersion,
	UserCredentialStatus status) {
}
