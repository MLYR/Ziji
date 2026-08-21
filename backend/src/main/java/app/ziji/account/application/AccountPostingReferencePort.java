package app.ziji.account.application;

import java.util.Optional;
import java.util.UUID;

/** 账务模块读取账户类别、币种和生命周期的公开端口。 */
public interface AccountPostingReferencePort {

	Optional<AccountPostingReference> findById(UUID accountId);

	/** 账务事实写入前必须锁定账户行；未实现锁语义的替身/适配器必须显式失败。 */
	default Optional<AccountPostingReference> findByIdForUpdate(UUID accountId) {
		throw new UnsupportedOperationException("账务账户锁定读取未实现。");
	}
}
