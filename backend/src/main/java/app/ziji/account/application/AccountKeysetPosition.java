package app.ziji.account.application;

import java.time.Instant;
import java.util.UUID;

/** 账户列表固定排序的 keyset 边界：createdAt DESC，再以 ID DESC 作为最终 tie-breaker。 */
public record AccountKeysetPosition(Instant createdAt, UUID accountId) {

	public AccountKeysetPosition {
		if (createdAt == null || accountId == null) {
			throw new AccountQueryValidationException("账户游标边界无效。");
		}
	}
}
