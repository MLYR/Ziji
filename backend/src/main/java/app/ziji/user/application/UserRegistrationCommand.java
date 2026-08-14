package app.ziji.user.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import app.ziji.user.domain.BaseCurrency;

/** 注册写入 users 所需的已校验数据；只接收 Argon2id 编码结果，不接收明文密码。 */
public final class UserRegistrationCommand {

	private final UUID userId;
	private final String email;
	private final String emailNormalized;
	private final String passwordHash;
	private final String nickname;
	private final ZoneId timezone;
	private final BaseCurrency baseCurrency;
	private final String locale;
	private final Instant registeredAt;

	public UserRegistrationCommand(
		UUID userId,
		String email,
		String emailNormalized,
		String passwordHash,
		String nickname,
		ZoneId timezone,
		String baseCurrency,
		String locale,
		Instant registeredAt) {
		this.userId = required(userId, "用户 ID");
		this.email = text(email, "邮箱", 1, 320);
		this.emailNormalized = text(emailNormalized, "规范化邮箱", 1, 320);
		if (passwordHash == null || !passwordHash.startsWith("$argon2id$")) {
			throw new UserPersistenceException(new IllegalArgumentException("密码 Hash 格式无效。"));
		}
		this.passwordHash = passwordHash;
		this.nickname = text(nickname, "昵称", 1, 100);
		this.timezone = required(timezone, "时区");
		this.baseCurrency = currency(baseCurrency);
		this.locale = text(locale, "语言", 2, 16);
		this.registeredAt = required(registeredAt, "注册时间");
	}

	public UUID userId() {
		return userId;
	}

	public String email() {
		return email;
	}

	public String emailNormalized() {
		return emailNormalized;
	}

	public String passwordHash() {
		return passwordHash;
	}

	public String nickname() {
		return nickname;
	}

	public ZoneId timezone() {
		return timezone;
	}

	public BaseCurrency baseCurrency() {
		return baseCurrency;
	}

	public String locale() {
		return locale;
	}

	public Instant registeredAt() {
		return registeredAt;
	}

	private static BaseCurrency currency(String value) {
		try {
			return BaseCurrency.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new UserPersistenceException(new IllegalArgumentException("基准币种无效。", exception));
		}
	}

	private static <T> T required(T value, String name) {
		if (value == null) {
			throw new UserPersistenceException(new IllegalArgumentException(name + "不能为空。"));
		}
		return value;
	}

	private static String text(String value, String name, int minLength, int maxLength) {
		if (value == null || value.isBlank() || value.length() < minLength || value.length() > maxLength) {
			throw new UserPersistenceException(new IllegalArgumentException(name + "格式无效。"));
		}
		return value;
	}
}
