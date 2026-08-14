package app.ziji.auth.application;

import java.time.Instant;

import app.ziji.auth.domain.SourceAddress;

/**
 * 密码登录应用命令；携带原始邮箱、明文密码、已解析来源地址和当前时间。
 * 明文密码只在本对象与服务校验/Hash 边界之间短暂存在，不得进入日志、审计或返回值。
 */
public record PasswordLoginCommand(
	String email,
	String password,
	SourceAddress sourceAddress,
	Instant now) {
}
