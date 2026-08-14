package app.ziji.auth.domain;

import java.text.Normalizer;
import java.util.Locale;

/** 邮箱规范化值对象；只做大小写无关规范化，不实现 Gmail 别名规则。 */
public final class EmailAddress {

	private final String normalized;

	private EmailAddress(String normalized) {
		this.normalized = normalized;
	}

	public static EmailAddress normalize(String value) {
		if (value == null) {
			throw new AuthDomainException("邮箱不能为空。");
		}
		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || normalized.length() > 320
			|| normalized.indexOf('@') <= 0
			|| normalized.indexOf('@') != normalized.lastIndexOf('@')
			|| normalized.endsWith("@")) {
			throw new AuthDomainException("邮箱格式无效。");
		}
		return new EmailAddress(normalized);
	}

	public String value() {
		return normalized;
	}
}
