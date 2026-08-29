package app.ziji.category.application;

import java.util.Optional;

/** 标签写端口；事实写入必须发生在调用方事务内。 */
public interface TagCommandStore {

	void insert(TagSnapshot tag);

	Optional<TagSnapshot> updateIfVersion(TagSnapshot tag, int expectedVersion);
}
