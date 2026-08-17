package app.ziji.ledger.application;

/** 交易读取参数或已认证查询游标无效。 */
public class TransactionQueryValidationException extends RuntimeException {

	public TransactionQueryValidationException() {
		super("交易查询参数无效。");
	}
}
