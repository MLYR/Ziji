package app.ziji.investment.application;

/** 账户或投资事实不在当前用户 ACTIVE membership 可见范围。 */
public class InvestmentNotVisibleException extends InvestmentApplicationException {

	public InvestmentNotVisibleException() {
		super("投资资源不存在或不可见。");
	}
}
