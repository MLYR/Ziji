package app.ziji.account.infrastructure;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import app.ziji.account.application.AccountCursorCodec;
import app.ziji.account.application.AccountArchiveService;
import app.ziji.account.application.AccountArchiveStore;
import app.ziji.account.application.AccountBalanceReadPort;
import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.account.application.AccountQueryService;
import app.ziji.account.application.AccountStore;
import app.ziji.account.application.AccountUpdatePort;
import app.ziji.account.application.LiquidityHoldCursorCodec;
import app.ziji.account.application.LiquidityHoldService;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在 infrastructure 装配账户创建应用服务，保持 application/domain 无框架依赖。 */
@Configuration(proxyBeanMethods = false)
class AccountInfrastructureConfiguration {

	@Bean
	AccountCreationService accountCreationService(
		TransactionRunner transactions,
		AccountStore accounts,
		AccountMemberInitPort memberInit,
		AccountLedgerInitializationPort ledgerInit,
		Clock clock) {
		// 生产路径始终由服务端生成 UUID；测试可直接替换工厂。
		return new AccountCreationService(transactions, accounts, memberInit, ledgerInit, clock, UUID::randomUUID);
	}

	@Bean
	AccountQueryService accountQueryService(
		AccountQueryReadPort accounts,
		AccountUpdatePort updates,
		AccountMembershipReadPort memberships,
		AccountCursorCodec cursors,
		TransactionRunner transactions,
		Clock clock) {
		// 查询/更新应用服务保持纯 Java，依赖只注入公开端口。
		return new AccountQueryService(accounts, updates, memberships, cursors, transactions, clock);
	}

	@Bean
	AccountCursorCodec accountCursorCodec(@Value("${ziji.account.cursor-key-base64}") String cursorKeyBase64) {
		try {
			// 专用 AES-256 密钥保证游标不泄露账户边界，也不复用认证、幂等或 outbox 密钥。
			return new AesGcmAccountCursorCodec(Base64.getDecoder().decode(cursorKeyBase64), new SecureRandom());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("账户游标密钥配置无效。", exception);
		}
	}

	@Bean
	LiquidityHoldService liquidityHoldService(
		AccountStore accounts,
		AccountMembershipReadPort memberships,
		LiquidityHoldStore holds,
		LiquidityHoldCursorCodec cursors,
		AuditLogWritePort auditLogs,
		TransactionRunner transactions,
		Clock clock) {
		// 事实、审计与幂等终态共享最外层 PostgreSQL 事务；服务本身不接触 jOOQ。
		return new LiquidityHoldService(accounts, memberships, holds, cursors, auditLogs, transactions, clock, UUID::randomUUID);
	}

	@Bean
	LiquidityHoldCursorCodec liquidityHoldCursorCodec(
		@Value("${ziji.liquidity-hold.cursor-key-base64}") String cursorKeyBase64) {
		try {
			// LiquidityHold 使用独立 AES-256 密钥，不能复用认证、幂等、outbox 或账户游标密钥。
			return new AesGcmLiquidityHoldCursorCodec(Base64.getDecoder().decode(cursorKeyBase64), new SecureRandom());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("流动性占用游标密钥配置无效。", exception);
		}
	}

	@Bean
	AccountArchiveService accountArchiveService(
		TransactionRunner transactions,
		AccountStore accounts,
		AccountArchiveStore archives,
		AccountMembershipReadPort memberships,
		AccountBalanceReadPort balances,
		AuditLogWritePort auditLogs,
		Clock clock) {
		// 归档与余额确认共享账户、membership、审计和条件写入事务，不创建新的账务事实。
		return new AccountArchiveService(
			transactions, accounts, archives, memberships, balances, auditLogs, clock);
	}
}
