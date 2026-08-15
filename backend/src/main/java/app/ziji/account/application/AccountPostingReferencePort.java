package app.ziji.account.application;

import java.util.Optional;
import java.util.UUID;

/** 账务模块读取账户类别、币种和生命周期的公开端口。 */
public interface AccountPostingReferencePort {

	Optional<AccountPostingReference> findById(UUID accountId);
}
