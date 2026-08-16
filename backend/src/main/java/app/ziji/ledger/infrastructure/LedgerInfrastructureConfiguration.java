package app.ziji.ledger.infrastructure;

import java.time.Clock;

import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.category.application.CategoryStore;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerOutbox;
import app.ziji.ledger.application.LedgerRequestIdProvider;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.domain.PostingService;
import app.ziji.shared.application.TransactionRunner;
import org.slf4j.MDC;
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
}
