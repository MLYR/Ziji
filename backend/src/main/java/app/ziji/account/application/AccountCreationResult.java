package app.ziji.account.application;

import java.util.UUID;

import app.ziji.account.domain.Account;

/** 创建账户后的最小结果；期初余额缺失时 openingTransactionId 固定为 null。 */
public record AccountCreationResult(Account account, UUID openingTransactionId) {

	public AccountCreationResult {
		if (account == null) {
			throw new AccountCreationException("账户创建结果无效。");
		}
	}
}
