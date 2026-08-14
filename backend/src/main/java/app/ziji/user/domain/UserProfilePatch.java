package app.ziji.user.domain;

import java.time.ZoneId;
import java.util.Optional;

/** 类型化的部分更新命令；Optional.empty 表示字段缺失而不是显式 null。 */
public final class UserProfilePatch {

	private final Optional<String> nickname;
	private final Optional<ZoneId> timezone;
	private final Optional<BaseCurrency> baseCurrency;
	private final Optional<String> locale;
	private final Optional<AmountFormat> amountFormat;

	public UserProfilePatch(
		Optional<String> nickname,
		Optional<ZoneId> timezone,
		Optional<BaseCurrency> baseCurrency,
		Optional<String> locale,
		Optional<AmountFormat> amountFormat) {
		this.nickname = text(nickname, "昵称", 1, 100);
		this.timezone = required(timezone, "时区字段");
		this.baseCurrency = required(baseCurrency, "基准币种字段");
		this.locale = text(locale, "语言", 2, 16);
		this.amountFormat = required(amountFormat, "金额格式字段");
		// 空 patch 不能形成无意义的 updated_at/version 变化，领域构造时直接拒绝。
		if (this.nickname.isEmpty() && this.timezone.isEmpty() && this.baseCurrency.isEmpty()
			&& this.locale.isEmpty() && this.amountFormat.isEmpty()) {
			throw new UserDomainException("用户资料更新至少包含一个可更新字段。");
		}
	}

	public Optional<String> nickname() {
		return nickname;
	}

	public Optional<ZoneId> timezone() {
		return timezone;
	}

	public Optional<BaseCurrency> baseCurrency() {
		return baseCurrency;
	}

	public Optional<String> locale() {
		return locale;
	}

	public Optional<AmountFormat> amountFormat() {
		return amountFormat;
	}

	public boolean isEmpty() {
		return nickname.isEmpty() && timezone.isEmpty() && baseCurrency.isEmpty()
			&& locale.isEmpty() && amountFormat.isEmpty();
	}

	private static <T> Optional<T> required(Optional<T> value, String field) {
		if (value == null) {
			throw new UserDomainException(field + "不能为空。");
		}
		return value;
	}

	private static Optional<String> text(
		Optional<String> value,
		String field,
		int minLength,
		int maxLength) {
		required(value, field + "字段");
		if (value.isPresent()) {
			String text = value.get();
			if (text.isBlank() || text.length() < minLength || text.length() > maxLength) {
				throw new UserDomainException(field + "格式无效。");
			}
		}
		return value;
	}
}
