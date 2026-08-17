package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 借款到账复用 transfer_details，但保留独立语义以免被普通资产转账修订路径误认。 */
public record LiabilityBorrowingWriteDetails(
	UUID assetAccountId,
	UUID liabilityAccountId,
	Money amount) implements TransactionWriteDetails {
}
