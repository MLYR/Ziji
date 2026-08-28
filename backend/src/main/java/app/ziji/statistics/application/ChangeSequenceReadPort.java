package app.ziji.statistics.application;

import java.util.UUID;

/** Dashboard 读取用户当前定向变更序列的最小端口；序列单调递增，无变更时为 0。 */
public interface ChangeSequenceReadPort {

	long latestSequence(UUID userId);
}
