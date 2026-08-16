package app.ziji.account.application;

import java.time.Instant;
import java.util.UUID;

/** 完整修订历史的固定 createdAt/ID 排序边界。 */
public record LiquidityHoldKeysetPosition(Instant createdAt, UUID holdId) {

	public LiquidityHoldKeysetPosition {
		if (createdAt == null || holdId == null) {
			throw new LiquidityHoldException.Validation();
		}
	}
}
