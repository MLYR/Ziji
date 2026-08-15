package app.ziji.account.application;

import java.util.UUID;

/** 账务命令所需的账户公开快照，避免跨模块泄漏 account.domain。 */
public record AccountPostingReference(
	UUID id,
	String accountClass,
	String currency,
	boolean active) {

	public AccountPostingReference {
		if (id == null || accountClass == null || currency == null) {
			throw new IllegalArgumentException("账户账务快照不完整。");
		}
	}
}
