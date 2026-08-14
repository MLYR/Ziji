package app.ziji.auth.application;

import java.util.UUID;

/** 已完成凭据认证后创建稳定会话的输入；不含密码、验证码或任何 HTTP 传输细节。 */
public final class CreateDeviceSessionCommand {

	private final UUID userId;
	private final String deviceName;
	private final String deviceId;

	public CreateDeviceSessionCommand(UUID userId, String deviceName, String deviceId) {
		this.userId = userId;
		this.deviceName = deviceName;
		this.deviceId = deviceId;
	}

	public UUID userId() {
		return userId;
	}

	public String deviceName() {
		return deviceName;
	}

	public String deviceId() {
		return deviceId;
	}
}
