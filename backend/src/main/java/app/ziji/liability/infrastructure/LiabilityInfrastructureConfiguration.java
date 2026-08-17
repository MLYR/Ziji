package app.ziji.liability.infrastructure;

import java.time.Clock;

import app.ziji.account.application.LiabilityAccountReferencePort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.liability.application.LiabilityDetailService;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 负债详情 application service 的最小装配；数据库访问隐藏在 adapter 后。 */
@Configuration(proxyBeanMethods = false)
class LiabilityInfrastructureConfiguration {

	@Bean
	LiabilityDetailService liabilityDetailService(
		LiabilityAccountReferencePort accounts,
		AccountMembershipReadPort memberships,
		PostgresLiabilityDetailStore details,
		UnifiedIdempotencyService idempotency,
		Clock clock) {
		return new LiabilityDetailService(accounts, memberships, details, idempotency, clock);
	}
}
