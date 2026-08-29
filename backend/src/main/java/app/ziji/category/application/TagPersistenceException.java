package app.ziji.category.application;

/** 标签事实读写失败；调用方必须映射 InternalError，不得静默降级。 */
public class TagPersistenceException extends RuntimeException {

	public TagPersistenceException(Throwable cause) {
		super(cause);
	}
}
