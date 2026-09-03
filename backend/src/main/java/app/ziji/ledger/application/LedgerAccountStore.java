package app.ziji.ledger.application;

import java.time.Instant;
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

	/** 投资入账使用固定语义系统科目，调用方不能提交任意系统科目 ID。 */
	default LedgerAccountReference ensureInvestmentIncomeAccount(
		UUID ownerUserId, app.ziji.ledger.domain.CurrencyCode currency) {
		throw new LedgerCommandValidationException("投资收益科目端口未实现。");
	}

	/** 投资费用和已实现亏损共用费用性质，但按固定代码保留可审计语义。 */
	default LedgerAccountReference ensureInvestmentExpenseAccount(
		UUID ownerUserId, app.ziji.ledger.domain.CurrencyCode currency, String kind) {
		throw new LedgerCommandValidationException("投资费用科目端口未实现。");
	}

	/** 投资账户必须同时具备 PRIMARY 与 POSITION_COST，成本科目不进入资产汇总。 */
	default Optional<LedgerAccountReference> findPositionCostForVisibleAccount(UUID accountId) {
		return Optional.empty();
	}

	Money currentBalance(UUID ledgerAccountId);

	/** 按交易固化 business_date 读取指定时点余额；只读实现不得锁行或写入余额投影。 */
	default Money balanceAt(UUID ledgerAccountId, Instant asOf) {
		throw new LedgerPersistenceException(new UnsupportedOperationException("指定时点余额读取未实现。"));
	}
}
