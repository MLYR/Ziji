package app.ziji.investment.interfaces;

import org.springframework.http.HttpStatus;

/** 投资写接口已判定的稳定 Problem；不把底层异常或资源内容带入响应。 */
final class InvestmentApiProblemException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final boolean retryAfter;

	InvestmentApiProblemException(HttpStatus status, String code, boolean retryAfter) {
		super(code);
		this.status = status;
		this.code = code;
		this.retryAfter = retryAfter;
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}

	boolean retryAfter() {
		return retryAfter;
	}
}
