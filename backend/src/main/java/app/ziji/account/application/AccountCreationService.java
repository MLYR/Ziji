package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.account.domain.Account;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.shared.application.TransactionRunner;

/**
 * 原子创建账户的应用编排：在同一最外层 TransactionRunner.required 事务内
 * 写入账户、OWNER 成员、100% 计入设置和所需账务科目。
 * 任一步写入失败整体回滚，数据库中不留下孤儿事实。
 */
public class AccountCreationService {

	private final TransactionRunner transactions;
	private final AccountStore accounts;
	private final AccountMemberInitPort memberInit;
	private final AccountLedgerInitializationPort ledgerInit;
	private final Clock clock;
	private final Supplier<UUID> ids;

	public AccountCreationService(
		TransactionRunner transactions,
		AccountStore accounts,
		AccountMemberInitPort memberInit,
		AccountLedgerInitializationPort ledgerInit,
		Clock clock,
		Supplier<UUID> ids) {
		if (transactions == null || accounts == null || memberInit == null
			|| ledgerInit == null || clock == null || ids == null) {
			throw new AccountCreationException("账户创建服务依赖不能为空。");
		}
		this.transactions = transactions;
		this.accounts = accounts;
		this.memberInit = memberInit;
		this.ledgerInit = ledgerInit;
		this.clock = clock;
		this.ids = ids;
	}

	/**
	 * 在单一事务内原子创建账户及其全部附属事实。
	 * 所有时间事实使用同一个 clock.instant()，所有 ID 由服务端生成。
	 * V007 延迟约束在事务提交时验证 OWNER、100% 计入和 PRIMARY 科目。
	 */
	public Account createAccount(AccountCreationCommand command) {
		return createAccountWithOpening(command).account();
	}

	/**
	 * 账户、成员、计入设置、科目和可选 OPENING 交易共享同一最外层事务；任一失败均不得留下局部事实。
	 */
	public AccountCreationResult createAccountWithOpening(AccountCreationCommand command) {
		if (command == null) {
			throw new AccountCreationException("创建账户命令不能为空。");
		}
		return transactions.required(() -> {
			// 统一时间事实：账户、成员、计入设置和科目使用同一个 now。
			Instant now = clock.instant();
			// 服务端 ID 工厂可由测试替换，避免把随机性混入业务断言。
			UUID accountId = ids.get();
			if (accountId == null) {
				throw new AccountCreationException("账户 ID 生成失败。");
			}

			// 1. Account.create 在领域边界校验 class/type 矩阵、名称、币种等。
			Account account = Account.create(
				accountId,
				command.accountClass(),
				command.accountType(),
				command.name(),
				command.institution(),
				command.currency(),
				command.note(),
				command.createdBy(),
				now);

			// 2. 写入账户聚合本身。
			accounts.insert(account);

			// 3. 分阶段写入 OWNER 成员和 100% 当前计入设置，失败时由最外层事务整体回滚。
			UUID membershipId = memberInit.initializeOwnerMembership(accountId, command.createdBy(), now);
			memberInit.initializeInitialInclusion(membershipId, command.createdBy(), now);

			// 4. 所有账户创建 PRIMARY，投资账户额外创建 POSITION_COST。
			ledgerInit.initializePrimary(
				account.id(), account.accountClass().name(), account.currency().name(), now);
			if (account.accountClass() == app.ziji.account.domain.AccountClass.INVESTMENT) {
				ledgerInit.initializePositionCost(account.id(), account.currency().name(), now);
			}

			UUID openingTransactionId = command.openingBalance() == null ? null
				: ledgerInit.postOpening(
					account.id(), account.accountClass().name(), account.currency().name(), account.createdBy(),
					command.openingBalance(), command.openingTimezone());
			return new AccountCreationResult(account, openingTransactionId);
		});
	}

	/** 仅用于同键重放恢复首次 AccountCreatedEnvelope 的内部 OPENING 标识。 */
	public UUID findOpeningTransactionId(UUID accountId) {
		if (accountId == null) {
			throw new AccountCreationException("账户 ID 不能为空。");
		}
		return ledgerInit.findOpeningTransactionId(accountId).orElse(null);
	}

}
