package app.ziji.category.application;

/** 分类创建端口；事实写入由基础设施在调用方事务内完成。 */
public interface CategoryCommandStore {

	void insert(CategorySnapshot category);
}
