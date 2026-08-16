package app.ziji.sync.infrastructure;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.Base64;

import app.ziji.accountmember.application.AccountRecipientReadPort;
import app.ziji.ledger.application.LedgerTransactionSyncReadPort;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.sync.application.ChangeLogStore;
import app.ziji.sync.application.SyncOutboxConsumer;
import app.ziji.sync.application.SyncOutboxStore;
import app.ziji.sync.application.SyncChangeQueryService;
import app.ziji.sync.application.SyncChangeReadPort;
import app.ziji.sync.application.SyncCursorCodec;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
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

	@Bean
	SyncCursorCodec syncCursorCodec(@Value("${ziji.account.cursor-key-base64}") String cursorKeyBase64) {
		try {
			// 复用既有 AES 密钥配置，但独立 AAD 域禁止账户游标与同步游标互换。
			return new AesGcmSyncCursorCodec(Base64.getDecoder().decode(cursorKeyBase64), new SecureRandom());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("同步游标密钥配置无效。", exception);
		}
	}

	@Bean
	SyncChangeQueryService syncChangeQueryService(SyncChangeReadPort changes, SyncCursorCodec cursors) {
		return new SyncChangeQueryService(changes, cursors);
	}
}
