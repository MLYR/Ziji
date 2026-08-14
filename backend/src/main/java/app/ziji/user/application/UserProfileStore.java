package app.ziji.user.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;

/** 用户资料持久化端口；jOOQ 只在 infrastructure 实现中出现。 */
public interface UserProfileStore {

	Optional<UserProfile> findById(UUID userId);

	Optional<UserProfile> updateIfVersion(
		UUID userId,
		int expectedVersion,
		UserProfilePatch patch,
		Instant updatedAt);
}
