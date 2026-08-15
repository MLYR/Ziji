package app.ziji.accountmember.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 账户模块读取当前 ACTIVE membership 角色与当前计入比例的公开端口。 */
public interface AccountMembershipReadPort {

	List<ActiveMembership> listActiveMemberships(UUID userId);

	Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId);

	/** 当前生效的成员视图；只包含仍有当前计入设置（valid_to IS NULL）的 ACTIVE 周期。 */
	record ActiveMembership(UUID accountId, String role, BigDecimal inclusionRatio) {

		public ActiveMembership {
			if (accountId == null || role == null || inclusionRatio == null) {
				throw new IllegalArgumentException("账户成员视图不完整。");
			}
		}
	}
}
