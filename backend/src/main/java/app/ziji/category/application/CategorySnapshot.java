package app.ziji.category.application;

import java.time.Instant;
import java.util.UUID;

/** categories 表事实快照；只由应用和适配器使用，不作为跨模块账务 DTO。 */
public record CategorySnapshot(
	UUID id,
	UUID ownerUserId,
	UUID accountId,
	CategoryType type,
	UUID parentId,
	String name,
	String nameNormalized,
	CategoryStatus status,
	UUID mergedIntoId,
	Instant createdAt,
	Instant updatedAt,
	int version) {

	public CategorySnapshot {
		if (id == null || type == null || name == null || nameNormalized == null
			|| status == null || createdAt == null || updatedAt == null || version < 1) {
			throw new IllegalArgumentException("分类事实快照不完整。");
		}
	}
}
