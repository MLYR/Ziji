package app.ziji.account.application;

import java.util.UUID;

/** 负债模块所需的最小账户分类快照，避免跨模块泄漏 account.domain。 */
public record LiabilityAccountReference(
	UUID id,
	String accountClass,
	String accountType,
	String currency) {

	public LiabilityAccountReference {
		if (id == null || accountClass == null || accountType == null || currency == null) {
			throw new IllegalArgumentException("负债账户快照不完整。");
		}
	}
}
