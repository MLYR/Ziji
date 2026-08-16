package app.ziji.sync.infrastructure;

import java.time.Clock;

import app.ziji.accountmember.application.AccountRecipientReadPort;
import app.ziji.ledger.application.LedgerTransactionSyncReadPort;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.sync.application.ChangeLogStore;
import app.ziji.sync.application.SyncOutboxConsumer;
import app.ziji.sync.application.SyncOutboxStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配同步消费者，保持其 application 层不依赖 Spring 或数据库类型。 */
@Configuration(proxyBeanMethods = false)
class SyncInfrastructureConfiguration {

	@Bean
	SyncOutboxConsumer syncOutboxConsumer(
		SyncOutboxStore outbox,
		ChangeLogStore changeLogs,
		LedgerTransactionSyncReadPort ledgerReads,
		AccountRecipientReadPort recipients,
		TransactionRunner transactions,
		Clock clock) {
		return new SyncOutboxConsumer(outbox, changeLogs, ledgerReads, recipients, transactions, clock);
	}

	@Bean
	ApplicationRunner syncOutboxStartupRecovery(SyncOutboxConsumer consumer) {
		return args -> consumer.consumeAvailable();
	}
}
