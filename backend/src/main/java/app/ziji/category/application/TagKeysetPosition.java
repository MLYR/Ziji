package app.ziji.category.application;

import java.time.Instant;
import java.util.UUID;

/** 标签列表固定排序 keyset 边界：createdAt DESC，再以 ID DESC 兜底。 */
public record TagKeysetPosition(Instant createdAt, UUID tagId) {

	public TagKeysetPosition {
		if (createdAt == null || tagId == null) {
			throw new TagValidationException();
		}
	}
}
