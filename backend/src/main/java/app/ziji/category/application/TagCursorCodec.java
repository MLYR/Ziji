package app.ziji.category.application;

import java.util.UUID;

/** 标签列表不透明 keyset 游标；实现必须绑定当前用户。 */
public interface TagCursorCodec {

	String encode(UUID userId, TagKeysetPosition position);

	TagKeysetPosition decode(UUID userId, String cursor);
}
