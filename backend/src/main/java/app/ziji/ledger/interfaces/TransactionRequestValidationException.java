package app.ziji.ledger.interfaces;

/** 交易写请求未满足冻结 OpenAPI 形状或头部格式。 */
final class TransactionRequestValidationException extends RuntimeException {

	TransactionRequestValidationException() {
		super("交易请求格式无效。");
	}
}
