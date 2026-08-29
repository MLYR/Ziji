package app.ziji.category.application;

import java.util.List;

/** 标签固定 keyset 分页结果。 */
public record TagPage(List<TagSnapshot> tags, String nextCursor, boolean hasMore) {

	public TagPage {
		tags = List.copyOf(tags);
	}
}
