package app.ziji.accountmember.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 按账户与变更发生时点读取可接收定向同步变更的成员周期。 */
public interface AccountRecipientReadPort {

	List<UUID> listRecipientUserIdsAt(UUID accountId, Instant occurredAt);
}
