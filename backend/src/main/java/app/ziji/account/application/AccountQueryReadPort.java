package app.ziji.account.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;

/** 账户查询只读端口；只读取 accounts 聚合，membership 视角由 accountmember 公开端口提供。 */
public interface AccountQueryReadPort {

	Optional<Account> findById(UUID accountId);

	List<Account> listByIds(
		Collection<UUID> accountIds,
		AccountKeysetPosition after,
		int maximumRecords);
}
