package app.ziji.account.application;

import java.util.UUID;

import app.ziji.account.domain.AccountPatch;

/** 账户查询与资料更新的最小应用端口，供 account interfaces 调用。 */
public interface AccountQueryUseCase {

	AccountPage listVisibleAccounts(UUID userId, Integer limit, String cursor);

	AccountQueryResult getVisibleAccount(UUID userId, UUID accountId);

	/** 写入前先固定对象可见性和 OWNER 权限，避免条件头泄露账户事实。 */
	default void authorizeUpdate(UUID userId, UUID accountId) {
		AccountQueryResult account = getVisibleAccount(userId, accountId);
		if (!"OWNER".equals(account.currentUserRole())) {
			throw new AccountPermissionDeniedException();
		}
	}

	AccountQueryResult updateAccount(UUID userId, UUID accountId, int expectedVersion, AccountPatch patch);
}
