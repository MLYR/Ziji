package app.ziji.ledger.application;

import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 可删除重建的单日账务余额投影视图，不是账务事实。 */
public record AccountBalanceSnapshot(UUID ledgerAccountId, LocalDate businessDate, Money balance) {

	public AccountBalanceSnapshot {
		if (ledgerAccountId == null || businessDate == null || balance == null) {
			throw new IllegalArgumentException("余额快照不完整。");
		}
	}
}
