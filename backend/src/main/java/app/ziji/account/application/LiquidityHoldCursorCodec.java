package app.ziji.account.application;

import java.util.UUID;

/** 绑定账户、固定过滤/排序和 API 主版本的不透明 LiquidityHold 游标。 */
public interface LiquidityHoldCursorCodec {

	String encode(UUID accountId, LiquidityHoldKeysetPosition position);

	LiquidityHoldKeysetPosition decode(UUID accountId, String cursor);
}
