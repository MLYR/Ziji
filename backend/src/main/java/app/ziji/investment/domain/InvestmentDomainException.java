package app.ziji.investment.domain;

/** 投资领域不变量被违反时抛出的业务异常。 */
public class InvestmentDomainException extends IllegalArgumentException {

	public InvestmentDomainException(String message) {
		super(message);
	}
}
