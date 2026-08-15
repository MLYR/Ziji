package app.ziji.account.domain;

/** 账户币种值类型，仅允许 V1 冻结的五种代码，不做大小写或空白转换。 */
public enum AccountCurrency {
	CNY,
	USD,
	HKD,
	JPY,
	EUR;

	/** 将外部代码严格转换为账户币种，拒绝空白和未冻结币种。 */
	public static AccountCurrency fromCode(String code) {
		if (code == null || code.isBlank()) {
			throw new AccountDomainException("账户币种不能为空。");
		}
		try {
			return valueOf(code);
		} catch (IllegalArgumentException exception) {
			throw new AccountDomainException("不支持的账户币种。");
		}
	}
}
