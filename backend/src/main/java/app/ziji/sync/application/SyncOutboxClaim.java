package app.ziji.sync.application;

import java.util.UUID;

/** 仅当前消费者持有的 receipt claim；完成或失败必须回写相同 token。 */
public record SyncOutboxClaim(String consumerName, SyncOutboxEvent event, UUID claimToken) {
	public SyncOutboxClaim {
		if (consumerName == null || consumerName.isBlank() || event == null || claimToken == null) {
			throw new IllegalArgumentException("同步 outbox claim 无效。");
		}
	}
}
