package app.ziji.ledger.application;

/** 交易或账户不属于当前用户 ACTIVE membership 的可见范围。 */
public class TransactionNotVisibleException extends RuntimeException {

	public TransactionNotVisibleException() {
		super("交易不存在或不可见。");
	}
}
