package app.ziji.category.application;

import java.util.Collection;
import java.util.UUID;

/** 标签公开查询端口；账务模块只校验标签归属和状态，不读取标签展示数据。 */
public interface TagStore {

	/** 返回属于 owner 且 ACTIVE 的标签数量；用于一笔交易多标签的原子校验。 */
	int countActiveOwned(Collection<UUID> tagIds, UUID ownerUserId);
}
