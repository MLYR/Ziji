package app.ziji.account.domain;

/** V002 与 OpenAPI 当前冻结的账户存储类型；产品标签到机器编码的缺口由 CHG-ACC-001 冻结。 */
public enum AccountType {
	BANK,
	WECHAT,
	ALIPAY,
	CASH,
	BROKERAGE,
	CREDIT_CARD,
	LOAN,
	OTHER;

	/** 只允许任务冻结的 class/type 配对，拒绝跨类组合。 */
	public boolean isAllowedFor(AccountClass accountClass) {
		if (accountClass == null) {
			throw new AccountDomainException("账户大类不能为空。");
		}
		return switch (accountClass) {
			case ASSET -> this == BANK || this == WECHAT || this == ALIPAY || this == CASH || this == OTHER;
			case INVESTMENT -> this == BROKERAGE || this == OTHER;
			case LIABILITY -> this == CREDIT_CARD || this == LOAN || this == OTHER;
		};
	}
}
