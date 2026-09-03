package app.ziji.investment.application;

/** 投资业务状态或领域规则不允许当前命令，接口层映射为 422。 */
public class InvestmentBusinessRuleException extends InvestmentApplicationException {

	public InvestmentBusinessRuleException(String message) {
		super(message);
	}
}
