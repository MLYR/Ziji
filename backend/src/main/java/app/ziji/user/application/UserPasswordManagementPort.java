package app.ziji.user.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 密码管理公开端口；auth 只依赖该最小凭据契约，不访问 users SQL 或 user infrastructure。 */
public interface UserPasswordManagementPort {

	Optional<UserCredential> findByUserIdForUpdate(UUID userId);

	/** 密码重置先锁定目标 users 行，再写入 Hash 和处置会话，保持认证并发锁序。 */
	Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized);

	Optional<UUID> updatePasswordByNormalizedEmail(String emailNormalized, String passwordHash, Instant updatedAt);

	void updatePasswordForUser(UUID userId, String passwordHash, Instant updatedAt);
}
