package app.ziji.investment.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import app.ziji.investment.application.InvestmentExchangeRatePort;
import org.springframework.stereotype.Component;

/** B4 汇率模块尚未提供事实时只允许同币种计算，跨币种明确返回缺失。 */
@Component
public final class NoopInvestmentExchangeRatePort implements InvestmentExchangeRatePort {

	@Override
	public Optional<BigDecimal> rate(String fromCurrency, String toCurrency, Instant asOf) {
		return fromCurrency != null && fromCurrency.equals(toCurrency) ? Optional.of(BigDecimal.ONE) : Optional.empty();
	}
}
