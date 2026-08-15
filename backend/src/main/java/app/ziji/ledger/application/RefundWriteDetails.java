package app.ziji.ledger.application;

import java.util.UUID;

/** refund_details 的无框架写入模型。 */
public record RefundWriteDetails(UUID originalTransactionId, UUID categoryId)
	implements TransactionWriteDetails {
}
