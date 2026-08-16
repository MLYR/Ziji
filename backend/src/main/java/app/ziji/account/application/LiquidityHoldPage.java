package app.ziji.account.application;

import java.util.List;

import app.ziji.account.domain.LiquidityHold;

/** 完整版本历史的 keyset 分页结果。 */
public record LiquidityHoldPage(List<LiquidityHold> holds, String nextCursor, boolean hasMore) {

	public LiquidityHoldPage {
		holds = List.copyOf(holds);
	}
}
