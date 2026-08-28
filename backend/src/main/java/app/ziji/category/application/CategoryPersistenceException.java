package app.ziji.category.application;

/** 分类持久化失败；只允许基础设施适配器在真实数据库异常时抛出。 */
public final class CategoryPersistenceException extends CategoryApplicationException {

	public CategoryPersistenceException(Throwable cause) {
		super(cause);
	}
}
