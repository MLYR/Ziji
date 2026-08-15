package app.ziji.ledger.application;

import java.util.Optional;
import java.util.UUID;

import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.Money;

/** 账务科目与事实余额查询端口；jOOQ 只允许存在 infrastructure。 */
public interface LedgerAccountStore {

	Optional<LedgerAccountReference> findById(UUID ledgerAccountId);

	Optional<LedgerAccountReference> findPrimaryForVisibleAccount(UUID accountId);

	Money currentBalance(UUID ledgerAccountId);
}
