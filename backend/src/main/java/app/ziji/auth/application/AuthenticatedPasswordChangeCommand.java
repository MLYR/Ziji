package app.ziji.auth.application;

import java.util.UUID;

/** 已认证改密输入；当前密码和新密码不进入结果、日志或异常文本。 */
public final class AuthenticatedPasswordChangeCommand {

	private final UUID userId;
	private final String currentPassword;
	private final String newPassword;

	public AuthenticatedPasswordChangeCommand(UUID userId, String currentPassword, String newPassword) {
		this.userId = userId;
		this.currentPassword = currentPassword;
		this.newPassword = newPassword;
	}

	public UUID userId() {
		return userId;
	}

	public String currentPassword() {
		return currentPassword;
	}

	public String newPassword() {
		return newPassword;
	}
}
