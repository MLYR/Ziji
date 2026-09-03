package app.ziji.accountmember.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 统计用的历史计入读取边界；调用方不能直接访问成员或计入设置表。 */
public interface AccountInclusionReadPort {

	/** 返回当前仍可见成员周期在指定业务时点生效的计入比例。 */
	List<MembershipInclusion> listIncludedAt(UUID userId, Instant businessAt);

	record MembershipInclusion(UUID accountId, BigDecimal ratio) {
		public MembershipInclusion {
			if (accountId == null || ratio == null || ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
				throw new IllegalArgumentException("历史计入视图无效。");
			}
		}
	}
}
