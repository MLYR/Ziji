package app.ziji.auth.application;

import java.util.UUID;

/** 注册成功的安全结果；不包含密码、Hash、验证码或认证凭据。 */
public final class EmailRegistrationResult {

	private final UUID userId;
	private final String email;
	private final String nickname;
	private final String timezone;
	private final String baseCurrency;
	private final String locale;

	public EmailRegistrationResult(
		UUID userId,
		String email,
		String nickname,
		String timezone,
		String baseCurrency,
		String locale) {
		this.userId = userId;
		this.email = email;
		this.nickname = nickname;
		this.timezone = timezone;
		this.baseCurrency = baseCurrency;
		this.locale = locale;
	}

	public UUID userId() {
		return userId;
	}

	public String email() {
		return email;
	}

	public String nickname() {
		return nickname;
	}

	public String timezone() {
		return timezone;
	}

	public String baseCurrency() {
		return baseCurrency;
	}

	public String locale() {
		return locale;
	}
}
