package app.ziji.accountmember.application;

import java.time.Instant;
import java.util.UUID;

/** 账务写入所需的成员周期和 OWNER/EDITOR 权限公开端口。 */
public interface AccountPostingAccessPort {

	boolean mayPost(UUID userId, UUID accountId, Instant effectiveAt);

	/** 最终账务写入复核需要区分不可见、只读和业务时间越界，避免统一降级为业务 422。 */
	default PostingAccessDecision postingDecision(UUID userId, UUID accountId, Instant effectiveAt) {
		return mayPost(userId, accountId, effectiveAt)
			? PostingAccessDecision.ALLOWED : PostingAccessDecision.OUTSIDE_PERIOD;
	}

	enum PostingAccessDecision {
		ALLOWED,
		NOT_VISIBLE,
		READ_ONLY,
		OUTSIDE_PERIOD
	}
}
