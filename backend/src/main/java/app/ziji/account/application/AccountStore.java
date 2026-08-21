package app.ziji.account.application;

import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;

/** 账户聚合持久化端口；jOOQ 只允许出现在 infrastructure 实现中。 */
public interface AccountStore {

	/**
	 * 写入账户聚合本身；调用方必须在同一外层事务补齐 OWNER、100% 计入设置和所需账务科目。
	 */
	void insert(Account account);

	Optional<Account> findById(UUID accountId);

	/** 写入流动性事实前必须锁定账户行；未实现锁语义的替身/适配器必须显式失败，不能静默降级。 */
	default Optional<Account> findByIdForUpdate(UUID accountId) {
		throw new UnsupportedOperationException("账户锁定读取未实现。");
	}
}
