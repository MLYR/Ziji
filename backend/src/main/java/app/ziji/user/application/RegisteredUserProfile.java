package app.ziji.user.application;

import java.util.UUID;

/** 注册幂等重放可安全重建的最小用户资料，不包含密码或任何认证凭据。 */
public record RegisteredUserProfile(
	UUID id,
	String email,
	String nickname,
	String timezone,
	String baseCurrency,
	String locale,
	String amountFormat,
	String status,
	int version) {
}
