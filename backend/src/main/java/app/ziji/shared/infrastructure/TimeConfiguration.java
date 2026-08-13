package app.ziji.shared.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

	@Bean
	Clock systemClock() {
		// 全系统统一注入 UTC Clock，测试可以替换为固定时钟。
		return Clock.systemUTC();
	}
}
