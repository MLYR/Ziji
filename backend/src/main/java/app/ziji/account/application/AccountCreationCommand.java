package app.ziji.account.application;

import java.time.ZoneId;
import java.util.UUID;

import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;

/**
 * 创建账户命令；账户 ID 与幂等键不进入命令，ID 由服务端在事务内生成。
 * 大类、子类型和币种直接使用领域枚举，在 Account.create 边界完成矩阵校验。
 */
public record AccountCreationCommand(
	AccountClass accountClass,
	AccountType accountType,
	String name,
	String institution,
	AccountCurrency currency,
	String note,
	UUID createdBy,
	AccountOpeningBalance openingBalance,
	ZoneId openingTimezone) {

	public AccountCreationCommand(
		AccountClass accountClass,
		AccountType accountType,
		String name,
		String institution,
		AccountCurrency currency,
		String note,
		UUID createdBy) {
		this(accountClass, accountType, name, institution, currency, note, createdBy, null, null);
	}

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
		if (openingBalance != null && openingTimezone == null) {
			throw new AccountCreationException("期初余额缺少当前用户时区。");
		}
	}
}
