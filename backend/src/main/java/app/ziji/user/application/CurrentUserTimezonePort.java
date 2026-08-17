package app.ziji.user.application;

import java.time.ZoneId;
import java.util.UUID;

/** 其他模块读取当前用户时区的最小公开端口，不泄漏 UserProfile 领域模型。 */
public interface CurrentUserTimezonePort {

	ZoneId currentTimezone(UUID userId);
}
