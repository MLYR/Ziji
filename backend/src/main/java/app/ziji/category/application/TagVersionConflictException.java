package app.ziji.category.application;

/** 标签乐观版本冲突；携带服务器当前版本供稳定错误摘要。 */
public class TagVersionConflictException extends RuntimeException {

	private final int currentVersion;

	public TagVersionConflictException(int currentVersion) {
		this.currentVersion = currentVersion;
	}

	public int currentVersion() {
		return currentVersion;
	}
}
