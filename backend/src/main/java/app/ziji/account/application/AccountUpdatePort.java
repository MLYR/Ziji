package app.ziji.account.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountPatch;

/** 账户资料条件更新端口；SQL 使用 version 条件并只允许成功一次。 */
public interface AccountUpdatePort {

	Optional<Account> updateIfVersion(
		UUID accountId,
		int expectedVersion,
		AccountPatch patch,
		Instant updatedAt);
}
