package app.ziji.ledger.infrastructure;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.category.application.CategoryStore;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.BalanceProjectionService;
import app.ziji.ledger.application.BalanceProjectionStore;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandPreflightService;
import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerOutbox;
import app.ziji.ledger.application.LedgerRequestIdProvider;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.application.TransactionCursorCodec;
import app.ziji.ledger.application.TransactionQueryReadPort;
import app.ziji.ledger.application.TransactionQueryService;
import app.ziji.ledger.domain.PostingService;
import app.ziji.shared.application.TransactionRunner;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在 infrastructure 装配账务应用服务，保持 application/domain 无框架依赖。 */
@Configuration(proxyBeanMethods = false)
class LedgerInfrastructureConfiguration {

	@Bean
	PostingService postingService() {
		return new PostingService();
	}

	@Bean
	LedgerCommandApplicationService ledgerCommandApplicationService(
		TransactionRunner transactions,
		AccountPostingReferencePort accounts,
		AccountPostingAccessPort accountAccess,
		CategoryStore categories,
		LedgerAccountStore ledgerAccounts,
		LedgerTransactionStore ledgerTransactions,
		AuditLogWritePort auditLogs,
		LedgerOutbox ledgerOutbox,
		LedgerRequestIdProvider requestIds,
		PostingService postingService,
		Clock clock) {
		return new LedgerCommandApplicationService(
			transactions,
			accounts,
			accountAccess,
			categories,
			ledgerAccounts,
			ledgerTransactions,
			auditLogs,
			ledgerOutbox,
			requestIds,
			postingService,
			clock);
	}

	@Bean
	LedgerCommandPreflightService ledgerCommandPreflightService(
		AccountPostingReferencePort accounts,
		AccountMembershipReadPort memberships,
		AccountPostingAccessPort postingAccess,
		LedgerAccountStore ledgerAccounts) {
		return new LedgerCommandPreflightService(accounts, memberships, postingAccess, ledgerAccounts);
	}

	@Bean
	BalanceProjectionService balanceProjectionService(
		TransactionRunner transactions,
		BalanceProjectionStore snapshots,
		Clock clock) {
		return new BalanceProjectionService(transactions, snapshots, clock);
	}

	@Bean
	LedgerRequestIdProvider ledgerRequestIdProvider() {
		return () -> {
			String requestId = MDC.get("requestId");
			// RequestIdFilter 或受控系统任务必须先绑定 correlation ID，不能由业务标识兜底。
			if (requestId == null || requestId.isBlank()) {
				throw new LedgerCommandValidationException("缺少当前受控请求标识。");
			}
			return requestId;
		};
	}

	@Bean
	TransactionQueryService transactionQueryService(
		TransactionQueryReadPort transactions,
		AccountMembershipReadPort memberships,
		CategoryStore categories,
		TransactionCursorCodec cursors) {
		return new TransactionQueryService(transactions, memberships, categories, cursors);
	}

	@Bean
	TransactionCursorCodec transactionCursorCodec(
		@Value("${ziji.transaction.cursor-key-base64:${ziji.account.cursor-key-base64}}") String cursorKeyBase64) {
		try {
			// 交易游标使用独立领域 AAD；未单独配置时仅复用现有 AES-256 密钥材料，避免新增启动硬依赖。
			return new AesGcmTransactionCursorCodec(Base64.getDecoder().decode(cursorKeyBase64), new SecureRandom());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("交易游标密钥配置无效。", exception);
		}
	}
}
