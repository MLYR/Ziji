package app.ziji.sync.application;

import java.util.List;
import java.util.UUID;

/** 当前用户的定向增量拉取；游标只确认已返回的 recipient sequence 边界。 */
public final class SyncChangeQueryService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;

	private final SyncChangeReadPort changes;
	private final SyncCursorCodec cursors;

	public SyncChangeQueryService(SyncChangeReadPort changes, SyncCursorCodec cursors) {
		if (changes == null || cursors == null) {
			throw new IllegalArgumentException("同步查询依赖不能为空。");
		}
		this.changes = changes;
		this.cursors = cursors;
	}

	public SyncChangePage list(UUID userId, Integer requestedLimit, String cursor) {
		if (userId == null) {
			throw new SyncQueryValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new SyncQueryValidationException();
		}

		long after = 0;
		if (cursor != null) {
			after = cursors.decode(userId, cursor);
			// 服务端只接受曾实际投递给该用户的边界，拒绝未来或其他 recipient 的合法密文。
			if (!changes.containsSequence(userId, after)) {
				throw new SyncQueryValidationException();
			}
		}

		List<SyncChange> rows = changes.listAfter(userId, after, limit + 1);
		boolean hasMore = rows.size() > limit;
		List<SyncChange> page = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
		String nextCursor = page.isEmpty() ? cursor : cursors.encode(userId, page.getLast().sequence());
		return new SyncChangePage(page, nextCursor, hasMore);
	}
}
