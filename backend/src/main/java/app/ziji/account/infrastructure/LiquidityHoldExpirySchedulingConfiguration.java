package app.ziji.account.infrastructure;

import java.time.Clock;

import app.ziji.account.application.LiquidityHoldExpiryFinalizer;
import app.ziji.account.application.LiquidityHoldExpiryFinalizerProperties;
import app.ziji.account.application.AccountStore;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.jooq.DSLContext;

/** enabled=false 时不注册调度器，避免普通测试和停用实例创建后台线程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "ziji.liquidity-hold.expiry-finalizer", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(LiquidityHoldExpiryFinalizerProperties.class)
class LiquidityHoldExpirySchedulingConfiguration {

	@Bean
	LiquidityHoldExpiryFinalizer liquidityHoldExpiryFinalizer(
		AccountStore accounts,
		LiquidityHoldStore holds,
		AuditLogWritePort auditLogs,
		TransactionRunner transactions,
		Clock clock) {
		return new LiquidityHoldExpiryFinalizer(accounts, holds, auditLogs, transactions, clock);
	}

	@Bean
	PostgresLiquidityHoldExpiryRunStore postgresLiquidityHoldExpiryRunStore(DSLContext dsl) {
		return new PostgresLiquidityHoldExpiryRunStore(dsl);
	}

	@Bean
	LiquidityHoldExpiryScheduler liquidityHoldExpiryScheduler(
		LiquidityHoldExpiryFinalizer finalizer,
		PostgresLiquidityHoldExpiryRunStore runs,
		LiquidityHoldExpiryFinalizerProperties properties,
		PlatformTransactionManager transactionManager,
		Clock clock) {
		return new LiquidityHoldExpiryScheduler(finalizer, runs, properties, transactionManager, clock);
	}
}
