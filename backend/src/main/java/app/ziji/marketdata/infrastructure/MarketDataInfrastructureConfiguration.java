package app.ziji.marketdata.infrastructure;

import java.time.Clock;
import java.time.Duration;

import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.marketdata.application.MarketDataReadPort;
import app.ziji.marketdata.application.MarketDataReadService;
import app.ziji.marketdata.application.MarketDataSyncService;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataQuotaPort;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 装配市场数据读写、同步用例和同花顺外部边界；同花顺公开端点无凭据。 */
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
		ThsMarketDataAdapter adapter,
		Clock clock) {
		return new MarketDataApplicationService(store, transactions, auditLogs, adapter, false, clock);
	}

	@Bean
	MarketDataSyncService marketDataSyncService(
		MarketDataCommandStore store,
		MarketDataSyncStore syncStore,
		ThsMarketDataAdapter adapter,
		TransactionRunner transactions,
		Clock clock) {
		return new MarketDataSyncService(store, syncStore, adapter, transactions, clock);
	}

	@Bean
	ThsTransport thsTransport() {
		return new JavaHttpThsTransport();
	}

	@Bean
	ThsRateLimiter thsRateLimiter(Clock clock) {
		return new ThsRateLimiter(clock, Duration.ofMillis(500), 2_000);
	}

	@Bean
	MarketDataQuotaPort marketDataQuotaPort(
		MarketDataSyncStore syncStore,
		@Value("${ziji.ths.daily-quota:2000}") int dailyQuota) {
		return new PostgresMarketDataQuotaGate(syncStore, dailyQuota);
	}

	@Bean
	ThsMarketDataAdapter thsMarketDataAdapter(
		ThsTransport transport,
		ObjectMapper objectMapper,
		ThsRateLimiter limiter,
		MarketDataQuotaPort quota,
		Clock clock,
		@Value("${ziji.ths.timeout-ms:3000}") long timeoutMs,
		@Value("${ziji.ths.max-retries:2}") int maxRetries) {
		return new ThsMarketDataAdapter(
			transport, objectMapper, Duration.ofMillis(timeoutMs), maxRetries, limiter, quota, clock);
	}
}
