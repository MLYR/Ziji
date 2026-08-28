package app.ziji.statistics.infrastructure;

import java.time.Clock;

import app.ziji.account.application.AccountBalanceFactReadPort;
import app.ziji.account.application.AccountBalanceSnapshotTransaction;
import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.account.application.LiquidityHoldBalanceReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.statistics.application.ChangeSequenceReadPort;
import app.ziji.statistics.application.DashboardApplicationService;
import app.ziji.statistics.application.StatisticsApplicationService;
import app.ziji.statistics.application.StatisticsFactReadPort;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Dashboard 与统计读取服务；事实聚合与账户摘要均经公开端口读取。 */
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

	@Bean
	StatisticsApplicationService statisticsApplicationService(
		AccountMembershipReadPort memberships,
		AccountQueryReadPort accounts,
		StatisticsFactReadPort facts,
		CurrentUserBaseCurrencyPort baseCurrencies) {
		return new StatisticsApplicationService(memberships, accounts, facts, baseCurrencies);
	}
}
