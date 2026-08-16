package app.ziji.account.application;

import java.util.UUID;

import app.ziji.account.domain.LiquidityHold;

/** 冻结的 LiquidityHold 查询、创建、修订和释放用例。 */
public interface LiquidityHoldUseCase {

	LiquidityHoldPage list(UUID userId, UUID accountId, Integer requestedLimit, String cursor);

	void preflightCreate(UUID userId, UUID accountId);

	void preflightMutation(UUID userId, UUID accountId, UUID holdId);

	LiquidityHold create(UUID userId, UUID accountId, LiquidityHoldCommand command, String requestId);

	LiquidityHold revise(UUID userId, UUID accountId, UUID holdId, int expectedVersion, LiquidityHoldCommand command, String requestId);

	LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId);

	LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion);
}
