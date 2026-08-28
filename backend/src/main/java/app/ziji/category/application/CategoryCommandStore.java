package app.ziji.category.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 分类创建端口；事实写入由基础设施在调用方事务内完成。 */
public interface CategoryCommandStore {

	void insert(CategorySnapshot category);

	Optional<CategorySnapshot> updateIfVersion(CategorySnapshot category, int expectedVersion);

	Optional<CategorySnapshot> markMergedIfVersion(
		UUID categoryId,
		UUID targetCategoryId,
		int expectedVersion,
		Instant updatedAt);
}
