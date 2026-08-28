package app.ziji.statistics.application;

import java.time.Instant;
import java.util.UUID;

/** Dashboard 读取用户定向变更序列的最小端口；序列单调递增，无变更时为 0。 */
public interface ChangeSequenceReadPort {

	/**
	 * asOf 为 null 时返回当前最大序列；非空时返回该时点前（changed_at ≤ asOf）的最大序列，
	 * 使历史 projectionAsOf 响应的数据截至序列与同一事实快照一致。
	 */
	long latestSequence(UUID userId, Instant asOf);
}
