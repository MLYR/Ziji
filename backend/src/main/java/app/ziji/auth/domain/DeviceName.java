package app.ziji.auth.domain;

import java.text.Normalizer;

/** 稳定设备会话的展示名称；只在应用边界做 NFKC 与 trim，数据库继续兜底长度约束。 */
public final class DeviceName {

	private static final int MAXIMUM_LENGTH = 100;

	private final String value;

	private DeviceName(String value) {
		this.value = value;
	}

	public static DeviceName of(String value) {
		if (value == null) {
			throw new AuthDomainException("设备名称不能为空。");
		}
		// 展示名称规范化后再校验，避免全角空格或兼容字符绕过长度和空值边界。
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (normalized.isBlank() || length(normalized) > MAXIMUM_LENGTH) {
			throw new AuthDomainException("设备名称格式无效。");
		}
		return new DeviceName(normalized);
	}

	public String value() {
		return value;
	}

	private static int length(String value) {
		return value.codePointCount(0, value.length());
	}
}
