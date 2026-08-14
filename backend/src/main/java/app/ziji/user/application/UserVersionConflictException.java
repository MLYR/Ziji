package app.ziji.user.application;

import app.ziji.user.domain.UserProfile;

/** 原子版本条件更新未命中时携带当前可见资料，用于构造有界冲突详情。 */
public final class UserVersionConflictException extends RuntimeException {

	private final UserProfile current;

	public UserVersionConflictException(UserProfile current) {
		super("用户资料版本已变化。");
		if (current == null) {
			throw new IllegalArgumentException("当前用户资料不能为空。");
		}
		this.current = current;
	}

	public UserProfile current() {
		return current;
	}
}
