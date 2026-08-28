package app.ziji.statistics.application;

import java.util.UUID;

/** Dashboard 只读用例端口；读取侧无幂等要求，但必须校验当前主体。 */
public interface DashboardQueryUseCase {

	DashboardResult getDashboard(UUID userId);
}
