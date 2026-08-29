package app.ziji.category.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 标签查询端口；只暴露当前用户可见事实。 */
public interface TagQueryReadPort {

	Optional<TagSnapshot> findByIdForUpdate(UUID tagId);

	boolean existsNameConflict(UUID ownerUserId, String nameNormalized, UUID excludeTagId);

	List<TagSnapshot> listOwner(UUID ownerUserId, TagKeysetPosition after, int maximumRecords);
}
