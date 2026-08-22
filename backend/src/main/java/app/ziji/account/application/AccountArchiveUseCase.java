package app.ziji.account.application;

import java.util.UUID;

/** 账户归档用例端口；接口层不直接访问账户、成员或 Ledger 持久化。 */
public interface AccountArchiveUseCase {

	void preflightAccess(UUID userId, UUID accountId);

	AccountQueryResult archive(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		String reason,
		boolean confirmNonZeroBalance,
		String requestId);

	AccountQueryResult replay(UUID userId, UUID accountId, int expectedVersion);
}
