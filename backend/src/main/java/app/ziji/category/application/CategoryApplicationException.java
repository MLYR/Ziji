package app.ziji.category.application;

/** 分类模块应用异常基类；HTTP 边界负责映射稳定错误码。 */
public abstract class CategoryApplicationException extends RuntimeException {

	protected CategoryApplicationException() {
	}

	protected CategoryApplicationException(Throwable cause) {
		super(cause);
	}
}
