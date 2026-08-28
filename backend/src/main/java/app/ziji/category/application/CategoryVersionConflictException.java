package app.ziji.category.application;

/** 分类资源实体版本已经变化；携带当前版本用于幂等终态和安全重试。 */
public final class CategoryVersionConflictException extends CategoryApplicationException {

	private final int currentVersion;

	public CategoryVersionConflictException(int currentVersion) {
		this.currentVersion = currentVersion;
	}

	public int currentVersion() {
		return currentVersion;
	}
}
