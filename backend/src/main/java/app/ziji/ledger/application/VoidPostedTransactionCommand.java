package app.ziji.ledger.application;

import java.util.UUID;

/** 已确认交易的作废命令；实际抵消由新增 REVERSAL 事实完成。 */
public record VoidPostedTransactionCommand(UUID userId, UUID transactionId, int expectedEntityVersion, String reason) {

	public VoidPostedTransactionCommand {
		if (userId == null || transactionId == null || expectedEntityVersion <= 0 || reason == null || reason.isBlank()) {
			throw new LedgerCommandValidationException("交易作废命令不完整。");
		}
	}
}
