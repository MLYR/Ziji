package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** balance_adjustment_details 的无框架写入模型。 */
public record BalanceAdjustmentWriteDetails(
	UUID accountId,
	Money beforeBalance,
	Money actualBalance,
	Money differenceAmount,
	String reason) implements TransactionWriteDetails {
}
