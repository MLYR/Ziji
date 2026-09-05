package app.ziji.marketdata.infrastructure;

import java.util.UUID;

import app.ziji.marketdata.application.MarketDataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 仅当显式开启时注册盘后同步调度器；执行时间由配置管理，不硬编码供应商更新时间。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ziji.ths.sync", name = "enabled", havingValue = "true")
@EnableScheduling
class MarketDataSchedulingConfiguration {

	@Bean
	ScheduledMarketDataSyncRunner scheduledMarketDataSyncRunner(MarketDataSyncService syncService) {
		return new ScheduledMarketDataSyncRunner(syncService);
	}

	static final class ScheduledMarketDataSyncRunner {

		private static final Logger LOG = LoggerFactory.getLogger(ScheduledMarketDataSyncRunner.class);

		private final MarketDataSyncService syncService;

		ScheduledMarketDataSyncRunner(MarketDataSyncService syncService) {
			this.syncService = syncService;
		}

		@Scheduled(cron = "${ziji.ths.sync.cron:0 30 17 * * MON-FRI}")
		public void runScheduled() {
			String correlationId = UUID.randomUUID().toString();
			String previousRequestId = MDC.get("requestId");
			MDC.put("requestId", correlationId);
			try {
				MarketDataSyncService.SyncSummary summary = syncService.syncIncremental();
				LOG.info("Market data sync completed: correlationId={} status={} instruments={} succeeded={} failed={} outcome={}",
					correlationId, summary.status(), summary.instrumentCount(),
					summary.succeededCount(), summary.failedCount(), summary.outcome());
			} catch (RuntimeException exception) {
				// 只记录异常类型；响应体或连接细节不得进入日志。
				LOG.error("Market data sync failed: correlationId={} exceptionType={}",
					correlationId, exception.getClass().getName());
			} finally {
				if (previousRequestId == null) {
					MDC.remove("requestId");
				} else {
					MDC.put("requestId", previousRequestId);
				}
			}
		}
	}
}
