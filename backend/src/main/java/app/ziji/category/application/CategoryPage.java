package app.ziji.category.application;

import java.util.List;

/** 分类列表稳定 keyset 分页结果。 */
public record CategoryPage(List<CategorySnapshot> categories, String nextCursor, boolean hasMore) {

	public CategoryPage {
		categories = List.copyOf(categories);
	}
}
