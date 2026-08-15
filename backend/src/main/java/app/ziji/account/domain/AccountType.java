package app.ziji.account.domain;

/** 账户子类型机器编码；FUND 仅投资账户，CONSUMER_LOAN 仅负债账户，OTHER 不得代替这两类。 */
public enum AccountType {
	BANK,
	WECHAT,
	ALIPAY,
	CASH,
	BROKERAGE,
	FUND,
	CREDIT_CARD,
	LOAN,
	CONSUMER_LOAN,
	OTHER;

	/** 只允许任务冻结的 class/type 配对，拒绝跨类组合。 */
	public boolean isAllowedFor(AccountClass accountClass) {
		if (accountClass == null) {
			throw new AccountDomainException("账户大类不能为空。");
		}
		return switch (accountClass) {
			case ASSET -> this == BANK || this == WECHAT || this == ALIPAY || this == CASH || this == OTHER;
			case INVESTMENT -> this == BROKERAGE || this == FUND || this == OTHER;
			case LIABILITY -> this == CREDIT_CARD || this == LOAN || this == CONSUMER_LOAN || this == OTHER;
		};
	}
}
