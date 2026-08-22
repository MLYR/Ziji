package app.ziji.account.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;

/** 账户生命周期条件写端口；归档只允许 ACTIVE 且版本匹配的账户转换一次。 */
public interface AccountArchiveStore {

	Optional<Account> archiveIfVersion(UUID accountId, int expectedVersion, Instant archivedAt);
}
