package app.ziji.account.application;

import java.time.Instant;
import java.util.UUID;

/** 账户账面余额与流动性可用余额的读取用例。 */
public interface AccountBalanceUseCase {

	AccountBalanceResult getBalance(UUID userId, UUID accountId, Instant requestedAsOf);
}
