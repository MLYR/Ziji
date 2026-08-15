package app.ziji.shared.application;

/** 幂等调用在进入数据库事务前发现的格式或安全边界错误；消息不包含请求内容或密钥。 */
public final class IdempotencyValidationException extends RuntimeException {

	public IdempotencyValidationException(String message) {
		super(message);
	}
}
