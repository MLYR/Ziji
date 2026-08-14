package app.ziji.user.domain;

import java.time.ZoneId;
import java.util.UUID;

/** 当前用户可见的非敏感资料；密码 Hash、验证码和 Token 不进入该模型。 */
public final class UserProfile {

	private final UUID id;
	private final String email;
	private final String nickname;
	private final ZoneId timezone;
	private final BaseCurrency baseCurrency;
	private final String locale;
	private final AmountFormat amountFormat;
	private final UserStatus status;
	private final int version;

	public UserProfile(
		UUID id,
		String email,
		String nickname,
		ZoneId timezone,
		BaseCurrency baseCurrency,
		String locale,
		AmountFormat amountFormat,
		UserStatus status,
		int version) {
		this.id = required(id, "用户 ID");
		this.email = requiredText(email, "邮箱");
		this.nickname = boundedText(nickname, "昵称", 1, 100);
		this.timezone = required(timezone, "时区");
		this.baseCurrency = required(baseCurrency, "基准币种");
		// locale 的最小长度与 API 基线一致，避免领域层接受无法表达的语言标识。
		this.locale = boundedText(locale, "语言", 2, 16);
		this.amountFormat = required(amountFormat, "金额格式");
		this.status = required(status, "用户状态");
		if (version < 1) {
			throw new UserDomainException("用户版本必须为正整数。");
		}
		this.version = version;
	}

	public UUID id() {
		return id;
	}

	public String email() {
		return email;
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

	public AmountFormat amountFormat() {
		return amountFormat;
	}

	public UserStatus status() {
		return status;
	}

	public int version() {
		return version;
	}

	public String etag() {
		return "\"" + version + "\"";
	}

	/** 只构造新的资料快照，不在领域对象上原地修改，也不触碰历史账务事实。 */
	public UserProfile apply(UserProfilePatch patch) {
		if (patch == null || patch.isEmpty()) {
			throw new UserDomainException("用户资料更新至少包含一个可更新字段。");
		}
		return new UserProfile(
			id,
			email,
			patch.nickname().orElse(nickname),
			patch.timezone().orElse(timezone),
			patch.baseCurrency().orElse(baseCurrency),
			patch.locale().orElse(locale),
			patch.amountFormat().orElse(amountFormat),
			status,
			version + 1);
	}

	private static <T> T required(T value, String field) {
		if (value == null) {
			throw new UserDomainException(field + "不能为空。");
		}
		return value;
	}

	private static String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new UserDomainException(field + "不能为空。");
		}
		return value;
	}

	private static String boundedText(String value, String field, int minLength, int maxLength) {
		if (value == null || value.isBlank() || value.length() < minLength || value.length() > maxLength) {
			throw new UserDomainException(field + "格式无效。");
		}
		return value;
	}
}
