package app.ziji.category.application;

import java.util.UUID;

/** 分类列表不透明 keyset 边界；实现必须绑定当前用户和列表过滤条件。 */
public interface CategoryCursorCodec {

	String encode(UUID userId, UUID accountIdFilter, CategoryKeysetPosition position);

	CategoryKeysetPosition decode(UUID userId, UUID accountIdFilter, String cursor);
}
