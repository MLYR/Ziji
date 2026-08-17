package app.ziji.liability.domain;

/** 负债详情领域规则的稳定失败类型，供 application 与 HTTP 边界区分 400/422。 */
public abstract class LiabilityDetailException extends RuntimeException {

	private LiabilityDetailException(String message) {
		super(message);
	}

	public static final class Validation extends LiabilityDetailException {
		public Validation() {
			super("负债详情格式或范围无效。");
		}
	}

	public static final class BusinessRule extends LiabilityDetailException {
		public BusinessRule() {
			super("负债详情违反账户类型或币种规则。");
		}
	}
}
