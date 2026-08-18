package app.ziji.accountmember.application;

import java.util.UUID;

/** 账务写入所需的当前 ACTIVE membership 与 OWNER/EDITOR 权限公开端口。 */
public interface AccountPostingAccessPort {

	boolean mayPost(UUID userId, UUID accountId);

	/** 最终账务写入复核只依据请求时当前 ACTIVE membership；businessAt 仅是账务事实归属。 */
	default PostingAccessDecision postingDecision(UUID userId, UUID accountId) {
		return mayPost(userId, accountId)
			? PostingAccessDecision.ALLOWED : PostingAccessDecision.NOT_VISIBLE;
	}

	enum PostingAccessDecision {
		ALLOWED,
		NOT_VISIBLE,
		READ_ONLY
	}
}
