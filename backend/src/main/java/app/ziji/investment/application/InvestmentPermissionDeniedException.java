package app.ziji.investment.application;

/** 当前用户可见但没有投资成交写权限。 */
public class InvestmentPermissionDeniedException extends InvestmentApplicationException {

	public InvestmentPermissionDeniedException() {
		super("当前成员无权写入投资账户。");
	}
}
