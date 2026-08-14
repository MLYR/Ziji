package app.ziji.user.application;

import java.util.UUID;

import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;

/** 当前用户资料查询和设置更新的最小应用端口，便于替换认证身份适配器。 */
public interface UserProfileUseCase {

	UserProfile getCurrentUser(UUID userId);

	UserProfile updateCurrentUser(UUID userId, int expectedVersion, UserProfilePatch patch);
}
