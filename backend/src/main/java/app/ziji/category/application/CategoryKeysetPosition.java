package app.ziji.category.application;

import java.time.Instant;
import java.util.UUID;

/** 分类列表固定排序的 keyset 边界：createdAt DESC，再以 ID DESC 作为最终 tie-breaker。 */
public record CategoryKeysetPosition(Instant createdAt, UUID categoryId) {

	public CategoryKeysetPosition {
		if (createdAt == null || categoryId == null) {
			throw new CategoryValidationException();
		}
	}
}
