package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** Sync 到 Ledger 的受控语义接口；调用方永远不能携带内部科目或分录。 */
public sealed interface SyncLedgerCommand permits SyncLedgerCommand.Income, SyncLedgerCommand.Expense,
	SyncLedgerCommand.Refund, SyncLedgerCommand.Transfer, SyncLedgerCommand.Revision, SyncLedgerCommand.Reverse {

	UUID userId();

	@org.springframework.modulith.NamedInterface("sync-command")
	record Income(UUID userId, UUID transactionId, UUID accountId, UUID categoryId, Money amount,
		Instant businessAt, LocalDate businessDate, String timezone, String counterparty, String note)
		implements SyncLedgerCommand {
	}

	@org.springframework.modulith.NamedInterface("sync-command")
	record Expense(UUID userId, UUID transactionId, UUID accountId, UUID categoryId, Money amount,
		Instant businessAt, LocalDate businessDate, String timezone, String merchant, String note)
		implements SyncLedgerCommand {
	}

	@org.springframework.modulith.NamedInterface("sync-command")
	record Refund(UUID userId, UUID transactionId, UUID accountId, UUID originalTransactionId, Money amount,
		Instant businessAt, LocalDate businessDate, String timezone, String note)
		implements SyncLedgerCommand {
	}

	@org.springframework.modulith.NamedInterface("sync-command")
	record Transfer(UUID userId, UUID transactionId, UUID fromAccountId, UUID toAccountId, UUID feeCategoryId,
		Money amount, Money feeAmount, Instant businessAt, LocalDate businessDate, String timezone, String note)
		implements SyncLedgerCommand {
	}

	@org.springframework.modulith.NamedInterface("sync-command")
	record Revision(UUID userId, UUID transactionId, int expectedEntityVersion, String reason,
		Replacement replacement) implements SyncLedgerCommand {
	}

	@org.springframework.modulith.NamedInterface("sync-command")
	record Reverse(UUID userId, UUID transactionId, int expectedEntityVersion, String reason)
		implements SyncLedgerCommand {
	}

	/**
	 * 修订替代载荷只有业务语义；替代 Transaction UUID 必须由 Ledger 在冲正事务内生成。
	 */
	@org.springframework.modulith.NamedInterface("sync-command")
	sealed interface Replacement permits Replacement.Income, Replacement.Expense, Replacement.Refund, Replacement.Transfer {

		@org.springframework.modulith.NamedInterface("sync-command")
		record Income(UUID accountId, UUID categoryId, Money amount,
			Instant businessAt, LocalDate businessDate, String timezone, String counterparty, String note)
			implements Replacement {
		}

		@org.springframework.modulith.NamedInterface("sync-command")
		record Expense(UUID accountId, UUID categoryId, Money amount,
			Instant businessAt, LocalDate businessDate, String timezone, String merchant, String note)
			implements Replacement {
		}

		@org.springframework.modulith.NamedInterface("sync-command")
		record Refund(UUID accountId, UUID originalTransactionId, Money amount,
			Instant businessAt, LocalDate businessDate, String timezone, String note)
			implements Replacement {
		}

		@org.springframework.modulith.NamedInterface("sync-command")
		record Transfer(UUID fromAccountId, UUID toAccountId, UUID feeCategoryId,
			Money amount, Money feeAmount, Instant businessAt, LocalDate businessDate, String timezone, String note)
			implements Replacement {
		}
	}
}
