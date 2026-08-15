package app.ziji.account.infrastructure;

import java.time.Clock;
import java.util.UUID;

import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountStore;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在 infrastructure 装配账户创建应用服务，保持 application/domain 无框架依赖。 */
@Configuration(proxyBeanMethods = false)
class AccountInfrastructureConfiguration {

	@Bean
	AccountCreationService accountCreationService(
		TransactionRunner transactions,
		AccountStore accounts,
		AccountMemberInitPort memberInit,
		AccountLedgerInitializationPort ledgerInit,
		Clock clock) {
		// 生产路径始终由服务端生成 UUID；测试可直接替换工厂。
		return new AccountCreationService(transactions, accounts, memberInit, ledgerInit, clock, UUID::randomUUID);
	}
}
