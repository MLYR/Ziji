package app.ziji.ledger.application;

import java.util.Optional;
import java.util.UUID;

import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.Money;

/** 账务科目与事实余额查询端口；jOOQ 只允许存在 infrastructure。 */
public interface LedgerAccountStore {

	Optional<LedgerAccountReference> findById(UUID ledgerAccountId);

	Optional<LedgerAccountReference> findPrimaryForVisibleAccount(UUID accountId);

	/** 在当前账务事务内按唯一键读取或确保分类专属系统对方科目。 */
	LedgerAccountReference ensureCategorySystemAccount(
		UUID ownerUserId, UUID categoryId, LedgerAccountNature nature, app.ziji.ledger.domain.CurrencyCode currency);

	/** 在当前账务事务内按唯一键读取或确保用户级期初权益系统科目。 */
	default LedgerAccountReference ensureOpeningEquityAccount(
		UUID ownerUserId, app.ziji.ledger.domain.CurrencyCode currency) {
		throw new LedgerCommandValidationException("期初权益科目端口未实现。");
	}

	/** 在当前账务事务内按唯一键读取或确保用户级余额调整权益科目。 */
	default LedgerAccountReference ensureBalanceAdjustmentEquityAccount(
		UUID ownerUserId, app.ziji.ledger.domain.CurrencyCode currency) {
		throw new LedgerCommandValidationException("余额调整权益科目端口未实现。");
	}

	Money currentBalance(UUID ledgerAccountId);
}
