package app.ziji.category.application;

import java.util.UUID;

/** 跨模块公开的分类事实快照。 */
public record CategoryReference(
	UUID id,
	UUID ownerUserId,
	UUID accountId,
	CategoryType type,
	boolean active) {

	public CategoryReference {
		if (id == null || type == null) {
			throw new IllegalArgumentException("分类事实不完整。");
		}
	}
}
