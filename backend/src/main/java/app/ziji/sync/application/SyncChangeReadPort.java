package app.ziji.sync.application;

import java.util.List;
import java.util.UUID;

/** 只按定向接收者和单调 sequence 读取 change_log，不接受账户或客户端用户过滤。 */
public interface SyncChangeReadPort {

	List<SyncChange> listAfter(UUID recipientUserId, long sequenceExclusive, int maximumRows);

	boolean containsSequence(UUID recipientUserId, long sequence);
}
