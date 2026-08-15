package app.ziji.account.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 新账户创建时初始化可见账务科目的公开端口。
 * 实现位于 ledger 模块，避免 account 反向依赖 ledger application 而形成模块环。
 */
public interface AccountLedgerInitializationPort {

	void initializePrimary(UUID accountId, String accountClass, String currency, Instant now);

	void initializePositionCost(UUID accountId, String currency, Instant now);
}
