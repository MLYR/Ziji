package app.ziji.investment.application;

/** 投资事实读取或适配器失败；不得将底层 SQL/供应商消息暴露给 API。 */
public class InvestmentPersistenceException extends InvestmentApplicationException {

	public InvestmentPersistenceException(Throwable cause) {
		super("投资事实读取失败。", cause);
	}
}
