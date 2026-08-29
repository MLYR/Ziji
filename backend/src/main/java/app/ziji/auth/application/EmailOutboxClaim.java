package app.ziji.auth.application;

import java.util.UUID;

/** 仅 EMAIL 消费者持有的 receipt claim；完成或失败必须回写相同 token。 */
public record EmailOutboxClaim(String consumerName, EmailOutboxEvent event, UUID claimToken) {
	public EmailOutboxClaim {
		if (consumerName == null || consumerName.isBlank() || event == null || claimToken == null) {
			throw new IllegalArgumentException("邮件 outbox claim 无效。");
		}
	}
}
