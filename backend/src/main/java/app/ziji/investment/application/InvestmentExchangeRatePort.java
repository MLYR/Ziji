package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** 投资估值所需的汇率公开读取端口；缺汇率时调用方必须显式处理。 */
public interface InvestmentExchangeRatePort {

	Optional<BigDecimal> rate(String fromCurrency, String toCurrency, Instant asOf);
}
