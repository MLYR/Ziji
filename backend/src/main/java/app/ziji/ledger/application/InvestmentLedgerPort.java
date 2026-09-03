package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.LedgerAccountReference;

/** Ledger 对投资模块公开的唯一成交入账端口；投资模块不接触分录或内部科目。 */
public interface InvestmentLedgerPort {

	InvestmentLedgerResult postInvestmentTrade(InvestmentLedgerCommand command);

	record InvestmentLedgerResult(UUID transactionId) {

		public InvestmentLedgerResult {
			if (transactionId == null) {
				throw new LedgerCommandValidationException("投资入账结果缺少交易 ID。");
			}
		}
	}

	/** 在 Ledger infrastructure 内解析投资账户的 PRIMARY 与 POSITION_COST 科目。 */
	interface AccountResolver {

		InvestmentAccountLedgers resolve(UUID investmentAccountId);
	}

	record InvestmentAccountLedgers(
		LedgerAccountReference primary,
		LedgerAccountReference positionCost) {
	}
}
