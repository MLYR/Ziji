package app.ziji.category.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 分类查询端口；只返回 category 表事实快照，不泄漏 jOOQ 或 SQL。 */
public interface CategoryQueryReadPort {

	Optional<CategorySnapshot> findById(UUID categoryId);

	/** 检查同一作用域、类型和父节点下规范化名称是否已占用；用于可预期的 409 预检。 */
	boolean existsNameConflict(
		UUID ownerUserId,
		UUID accountId,
		CategoryType categoryType,
		UUID parentId,
		String nameNormalized);

	List<CategorySnapshot> listVisible(
		UUID userId,
		Collection<UUID> activeAccountIds,
		UUID accountIdFilter,
		CategoryKeysetPosition after,
		int maximumRecords);
}
