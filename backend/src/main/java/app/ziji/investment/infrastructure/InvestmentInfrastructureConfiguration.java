package app.ziji.investment.infrastructure;

import java.time.Clock;

import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountInclusionReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentExchangeRatePort;
import app.ziji.investment.application.InvestmentExternalCashFlowPort;
import app.ziji.investment.application.InvestmentFactReadPort;
import app.ziji.investment.application.InvestmentMarketDataPort;
import app.ziji.investment.application.InvestmentValuationRevisionPort;
import app.ziji.ledger.application.InvestmentCashReadPort;
import app.ziji.ledger.application.InvestmentLedgerPort;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;
import app.ziji.user.application.CurrentUserTimezonePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配投资应用用例；跨模块数据均经公开 application port 注入。 */
@Configuration(proxyBeanMethods = false)
class InvestmentInfrastructureConfiguration {

	@Bean
	InvestmentApplicationService investmentApplicationService(
		AccountMembershipReadPort memberships,
		AccountInclusionReadPort inclusions,
		AccountPostingReferencePort accounts,
		AccountPostingAccessPort postingAccess,
		InvestmentFactReadPort facts,
		InvestmentLedgerPort ledger,
		InvestmentMarketDataPort marketData,
			InvestmentCashReadPort cashBalances,
			InvestmentExternalCashFlowPort externalCashFlows,
			InvestmentExchangeRatePort exchangeRates,
			InvestmentValuationRevisionPort valuationRevisions,
			TransactionRunner transactions,
			CurrentUserBaseCurrencyPort baseCurrencies,
		CurrentUserTimezonePort timezones,
		Clock clock) {
		return new InvestmentApplicationService(
				memberships, inclusions, accounts, postingAccess, facts, ledger, marketData, cashBalances, externalCashFlows,
				exchangeRates, valuationRevisions, transactions, baseCurrencies, timezones, clock);
		}
}
