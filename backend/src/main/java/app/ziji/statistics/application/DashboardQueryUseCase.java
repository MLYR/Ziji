package app.ziji.statistics.application;

import java.time.Instant;
import java.util.UUID;

/** Dashboard 只读用例端口；读取侧无幂等要求，但必须校验当前主体。 */
public interface DashboardQueryUseCase {

	/**
	 * requestedAsOf 为 null 时返回当前指标；非空时按该时点从事实重建历史指标。
	 * 余额、账户可见性、流动性占用与变更序列共享同一数据库快照。
	 */
	DashboardResult getDashboard(UUID userId, Instant requestedAsOf);
}
