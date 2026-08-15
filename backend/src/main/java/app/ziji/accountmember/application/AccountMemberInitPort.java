package app.ziji.accountmember.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 原子创建账户时初始化 OWNER 成员周期和 100% 当前计入设置的公开端口。
 * 调用方必须在外层事务中调用，确保 V007 延迟约束在提交时通过。
 */
public interface AccountMemberInitPort {

	/**
	 * 为账户创建者写入 ACTIVE OWNER membership（membership_no=1, version=1）。
	 *
	 * @param accountId 账户 ID
	 * @param userId    创建者用户 ID
	 * @param now       所有时间事实使用的统一时间戳
	 */
	UUID initializeOwnerMembership(UUID accountId, UUID userId, Instant now);

	/**
	 * 为刚创建的成员周期写入 included=true、ratio=1.000000 的当前计入设置。
	 */
	void initializeInitialInclusion(UUID membershipId, UUID userId, Instant now);
}
