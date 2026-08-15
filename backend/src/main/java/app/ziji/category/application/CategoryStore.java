package app.ziji.category.application;

import java.util.Optional;
import java.util.UUID;

/** 分类公开查询端口，避免账务模块直接访问 categories 表。 */
public interface CategoryStore {

	Optional<CategoryReference> findById(UUID categoryId);
}
