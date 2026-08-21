package app.ziji.account.application;

import java.util.UUID;

import app.ziji.account.domain.LiquidityHold;

/** 冻结的 LiquidityHold 查询、创建、修订和释放用例。 */
public interface LiquidityHoldUseCase {

	LiquidityHoldPage list(UUID userId, UUID accountId, Integer requestedLimit, String cursor);

	/** 仅校验当前 membership、账户可见性与写角色；它必须先于既有幂等终态识别执行。 */
	void preflightCreateAccess(UUID userId, UUID accountId);

	void preflightCreate(UUID userId, UUID accountId);

	/** 修订/释放在幂等重放前只校验当前可见性与角色，不能读取可变账户状态。 */
	void preflightMutationAccess(UUID userId, UUID accountId, UUID holdId, int expectedVersion);

	/** 五参数调用一律按修订处理，归档账户只有显式 release 路径才可放行。 */
	default void preflightMutation(
		UUID userId,
		UUID accountId,
		UUID holdId,
		int expectedVersion) {
		preflightMutation(userId, accountId, holdId, expectedVersion, false);
	}

	/** 新请求的幂等前置访问校验；归档放行标记继续传递给真实 release 事务，不能在此提前消费。 */
	void preflightMutation(UUID userId, UUID accountId, UUID holdId, int expectedVersion, boolean allowArchivedAccount);

	LiquidityHold create(UUID userId, UUID accountId, LiquidityHoldCommand command, String requestId);

	LiquidityHold revise(UUID userId, UUID accountId, UUID holdId, int expectedVersion, LiquidityHoldCommand command, String requestId);

	LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId);

	LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion);
}
