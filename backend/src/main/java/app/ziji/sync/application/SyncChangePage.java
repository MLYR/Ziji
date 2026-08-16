package app.ziji.sync.application;

import java.util.List;

/** 服务端 sequence 的稳定增量页；空页沿用已确认游标，便于后续继续轮询。 */
public record SyncChangePage(List<SyncChange> changes, String nextCursor, boolean hasMore) {

	public SyncChangePage {
		changes = List.copyOf(changes);
	}
}
