package app.ziji.account.application;

import java.util.UUID;

import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;

/**
 * 创建账户命令；不含余额、ID 或幂等键，ID 由服务端在事务内生成。
 * 大类、子类型和币种直接使用领域枚举，在 Account.create 边界完成矩阵校验。
 */
public record AccountCreationCommand(
	AccountClass accountClass,
	AccountType accountType,
	String name,
	String institution,
	AccountCurrency currency,
	String note,
	UUID createdBy) {

	public AccountCreationCommand {
		if (accountClass == null) {
			throw new AccountCreationException("账户大类不能为空。");
		}
		if (accountType == null) {
			throw new AccountCreationException("账户子类型不能为空。");
		}
		if (currency == null) {
			throw new AccountCreationException("账户币种不能为空。");
		}
		if (createdBy == null) {
			throw new AccountCreationException("创建人不能为空。");
		}
	}
}
