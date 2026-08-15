package app.ziji.shared.infrastructure;

import java.time.Clock;

import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在 infrastructure 装配统一幂等 service，application 类型保持对 Spring 和 jOOQ 零依赖。 */
@Configuration(proxyBeanMethods = false)
class IdempotencyConfiguration {

	@Bean
	UnifiedIdempotencyService unifiedIdempotencyService(
		TransactionRunner transactionRunner,
		IdempotencyRecordStore recordStore,
		IdempotencyAnonymousSubjectHasher anonymousSubjectHasher,
		Clock clock) {
		return new UnifiedIdempotencyService(transactionRunner, recordStore, anonymousSubjectHasher, clock);
	}
}
