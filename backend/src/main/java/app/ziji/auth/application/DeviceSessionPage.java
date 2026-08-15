package app.ziji.auth.application;

import java.util.List;

/** 设备会话的稳定 keyset 分页结果；cursor 只指向同一用户可见的上一条会话。 */
public record DeviceSessionPage(List<DeviceSessionSummary> sessions, String nextCursor, boolean hasMore) {

	public DeviceSessionPage {
		sessions = List.copyOf(sessions);
	}
}
