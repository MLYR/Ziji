package app.ziji.user.application;

import java.util.UUID;

/** 其他模块读取当前用户基准币种的最小公开端口，不泄漏 UserProfile 领域模型。 */
public interface CurrentUserBaseCurrencyPort {

	String currentBaseCurrency(UUID userId);
}
