package app.ziji.account.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * 新账户创建时初始化可见账务科目的公开端口。
 * 实现位于 ledger 模块，避免 account 反向依赖 ledger application 而形成模块环。
 */
public interface AccountLedgerInitializationPort {

	void initializePrimary(UUID accountId, String accountClass, String currency, Instant now);

	void initializePositionCost(UUID accountId, String currency, Instant now);

	/**
	 * 账户模块只提交期初余额业务语义；Ledger 在当前最外层事务内决定系统对方科目与分录方向。
	 */
	default UUID postOpening(
		UUID accountId,
		String accountClass,
		String currency,
		UUID createdBy,
		AccountOpeningBalance openingBalance,
		ZoneId timezone) {
		throw new AccountCreationException("当前账务初始化端口不支持期初余额入账。");
	}

	/** 幂等重放只读取已入账的内部 OPENING 事实，不能由 Controller 查询 ledger 表。 */
	default Optional<UUID> findOpeningTransactionId(UUID accountId) {
		return Optional.empty();
	}
}
