package app.ziji.investment.application;

/** 投资应用层的统一异常基类，接口层据此映射稳定 HTTP 语义。 */
public class InvestmentApplicationException extends IllegalArgumentException {

	public InvestmentApplicationException(String message) {
		super(message);
	}

	public InvestmentApplicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
