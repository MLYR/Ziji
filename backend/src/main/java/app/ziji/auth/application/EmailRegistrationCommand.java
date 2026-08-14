package app.ziji.auth.application;

/** 邮箱注册应用输入；验证码和密码只在调用栈短暂存在，禁止提供可记录的 toString。 */
public final class EmailRegistrationCommand {

	private final String email;
	private final String verificationCode;
	private final String password;
	private final String nickname;
	private final String timezone;
	private final String baseCurrency;
	private final String locale;

	public EmailRegistrationCommand(
		String email,
		String verificationCode,
		String password,
		String nickname,
		String timezone,
		String baseCurrency,
		String locale) {
		this.email = email;
		this.verificationCode = verificationCode;
		this.password = password;
		this.nickname = nickname;
		this.timezone = timezone;
		this.baseCurrency = baseCurrency;
		this.locale = locale;
	}

	public String email() {
		return email;
	}

	public String verificationCode() {
		return verificationCode;
	}

	public String password() {
		return password;
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
