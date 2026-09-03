package app.ziji.marketdata.infrastructure;

import java.time.Clock;
import java.time.Duration;

import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.marketdata.application.MarketDataReadPort;
import app.ziji.marketdata.application.MarketDataReadService;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 装配市场数据读写用例和外部 Tushare 边界；token 只进入服务端适配器内存。 */
@Configuration(proxyBeanMethods = false)
class MarketDataInfrastructureConfiguration {

	@Bean
	MarketDataReadPort marketDataReadPort(MarketDataCommandStore store, Clock clock) {
		return new MarketDataReadService(store, clock);
	}

	@Bean
	MarketDataApplicationService marketDataApplicationService(
		MarketDataCommandStore store,
		TransactionRunner transactions,
		AuditLogWritePort auditLogs,
		Clock clock) {
		return new MarketDataApplicationService(store, transactions, auditLogs, clock);
	}

	@Bean
	TushareTransport tushareTransport() {
		return new JavaHttpTushareTransport();
	}

	@Bean
	TushareRateLimiter tushareRateLimiter(Clock clock) {
		return new TushareRateLimiter(clock, Duration.ofMillis(200), 2_000);
	}

	@Bean
	TushareMarketDataAdapter tushareMarketDataAdapter(
		TushareTransport transport,
		ObjectMapper objectMapper,
		TushareRateLimiter limiter,
		Clock clock,
		@Value("${ziji.tushare.endpoint:https://api.tushare.pro}") String endpoint,
		@Value("${ziji.tushare.token:}") String token,
		@Value("${ziji.tushare.timeout-ms:3000}") long timeoutMs,
		@Value("${ziji.tushare.max-retries:2}") int maxRetries) {
		return new TushareMarketDataAdapter(
			transport, objectMapper, endpoint, token, Duration.ofMillis(timeoutMs), maxRetries, limiter, clock);
	}
}
