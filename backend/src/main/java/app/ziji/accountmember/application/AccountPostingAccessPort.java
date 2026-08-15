package app.ziji.accountmember.application;

import java.time.Instant;
import java.util.UUID;

/** 账务写入所需的成员周期和 OWNER/EDITOR 权限公开端口。 */
public interface AccountPostingAccessPort {

	boolean mayPost(UUID userId, UUID accountId, Instant effectiveAt);
}
