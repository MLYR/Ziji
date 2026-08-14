package app.ziji.auth.domain;

/** 不透明稳定设备标识；保留客户端原值，绝不执行 trim 或 NFKC 改写。 */
public final class DeviceId {

	private static final int MAXIMUM_LENGTH = 200;

	private final String value;

	private DeviceId(String value) {
		this.value = value;
	}

	public static DeviceId ofNullable(String value) {
		if (value == null) {
			return null;
		}
		// 只检查边界，不改变原值；同一原值才是会话替换的可信比较对象。
		if (value.isBlank() || length(value) > MAXIMUM_LENGTH) {
			throw new AuthDomainException("设备标识格式无效。");
		}
		return new DeviceId(value);
	}

	public String value() {
		return value;
	}

	private static int length(String value) {
		return value.codePointCount(0, value.length());
	}
}
