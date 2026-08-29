package app.ziji.auth.infrastructure;

import app.ziji.auth.application.EmailChallengeOutboxConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** enabled=false 时不注册调度器和启动恢复，避免普通测试和停用实例创建后台线程或抢占 SMTP。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ziji.email-delivery", name = "enabled", havingValue = "true")
@EnableScheduling
class EmailDeliverySchedulingConfiguration {

	@Bean
	ApplicationRunner emailOutboxStartupRecovery(EmailChallengeOutboxConsumer consumer) {
		return args -> consumer.consumeAvailable();
	}

	@Bean
	EmailDeliveryScheduler emailDeliveryScheduler(EmailChallengeOutboxConsumer consumer) {
		return new EmailDeliveryScheduler(consumer);
	}

	/** 固定间隔排空可领取验证码事件；只记录数量和异常类型，不输出验证码。 */
	static final class EmailDeliveryScheduler {

		private static final Logger LOG = LoggerFactory.getLogger(EmailDeliveryScheduler.class);

		private final EmailChallengeOutboxConsumer consumer;

		EmailDeliveryScheduler(EmailChallengeOutboxConsumer consumer) {
			this.consumer = consumer;
		}

		@Scheduled(
			initialDelayString = "${ziji.email-delivery.initial-delay}",
			fixedDelayString = "${ziji.email-delivery.fixed-delay}")
		public void deliverScheduled() {
			try {
				int delivered = consumer.consumeAvailable();
				if (delivered > 0) {
					LOG.info("Email challenge delivery drained: count={}", delivered);
				}
			} catch (RuntimeException exception) {
				// 消费者已将单条失败持久化为重试或终态；此处只记录进程级异常类型。
				LOG.error("Email challenge delivery failed: exceptionType={}", exception.getClass().getName());
			}
		}
	}
}
