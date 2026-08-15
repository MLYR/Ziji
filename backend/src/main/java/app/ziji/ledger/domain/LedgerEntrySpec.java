package app.ziji.ledger.domain;

import java.util.UUID;

/** 语义命令在构造 Transaction 前使用的分录规格。 */
public record LedgerEntrySpec(UUID ledgerAccountId, LedgerDirection direction, Money amount) {

	public LedgerEntrySpec {
		if (ledgerAccountId == null || direction == null || amount == null) {
			throw new LedgerDomainException("分录规格不能为空。");
		}
	}
}
