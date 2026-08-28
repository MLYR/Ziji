package app.ziji.statistics.infrastructure;

import java.time.Clock;

import app.ziji.account.application.AccountBalanceFactReadPort;
import app.ziji.account.application.AccountBalanceSnapshotTransaction;
import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.account.application.LiquidityHoldBalanceReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.statistics.application.ChangeSequenceReadPort;
import app.ziji.statistics.application.DashboardApplicationService;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Dashboard 读取服务；账户摘要经账户模块公开端口读取，不接触领域模型。 */
@Configuration(proxyBeanMethods = false)
class StatisticsInfrastructureConfiguration {

	@Bean
	DashboardApplicationService dashboardApplicationService(
		AccountMembershipReadPort memberships,
		AccountQueryReadPort accounts,
		AccountBalanceFactReadPort ledgerBalances,
		LiquidityHoldBalanceReadPort holdBalances,
		CurrentUserBaseCurrencyPort baseCurrencies,
		ChangeSequenceReadPort changeSequences,
		AccountBalanceSnapshotTransaction snapshots,
		Clock clock) {
		return new DashboardApplicationService(
			memberships, accounts::listClassSummariesByIds, ledgerBalances, holdBalances,
			baseCurrencies, changeSequences, snapshots, clock);
	}
}
