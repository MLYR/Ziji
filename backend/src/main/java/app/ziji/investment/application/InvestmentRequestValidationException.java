package app.ziji.investment.application;

/** 投资 HTTP 请求格式或查询参数不合法。 */
public class InvestmentRequestValidationException extends InvestmentApplicationException {

	public InvestmentRequestValidationException() {
		super("投资请求校验失败。");
	}

	public InvestmentRequestValidationException(String message) {
		super(message);
	}
}
