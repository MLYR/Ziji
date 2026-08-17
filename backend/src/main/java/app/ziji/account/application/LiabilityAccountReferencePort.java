package app.ziji.account.application;

import java.util.Optional;
import java.util.UUID;

/** 负债模块只读账户类型与币种的公开端口。 */
public interface LiabilityAccountReferencePort {

	Optional<LiabilityAccountReference> findById(UUID accountId);
}
