package app.ziji.category.application;

import java.time.Instant;
import java.util.UUID;

/** tags 表事实快照；只供分类模块应用边界和 HTTP DTO 使用。 */
public record TagSnapshot(
	UUID id,
	UUID ownerUserId,
	String name,
	String nameNormalized,
	TagStatus status,
	Instant createdAt,
	Instant updatedAt,
	int version) {

	public TagSnapshot {
		if (id == null || ownerUserId == null || name == null || nameNormalized == null
			|| status == null || createdAt == null || updatedAt == null || version < 1) {
			throw new IllegalArgumentException("标签事实快照不完整。");
		}
	}
}
