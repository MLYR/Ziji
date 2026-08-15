package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** transfer_details 的无框架写入模型。 */
public record TransferWriteDetails(
	UUID fromAccountId,
	UUID toAccountId,
	Money fromAmount,
	Money toAmount,
	Money feeAmount) implements TransactionWriteDetails {
}
