package app.ziji.user.application;

import java.util.Optional;
import java.util.UUID;

/** 认证模块重放注册成功响应的公开 application 端口，禁止读取 user infrastructure。 */
public interface UserRegistrationReplayPort {

	Optional<RegisteredUserProfile> findRegisteredUserForReplay(UUID userId);
}
