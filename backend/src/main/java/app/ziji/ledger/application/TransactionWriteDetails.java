package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Money;

/** 交易类型特有的一对一明细；持久化适配器只接收这些无框架数据。 */
public sealed interface TransactionWriteDetails
	permits NoTransactionDetails, TransferWriteDetails, RefundWriteDetails, BalanceAdjustmentWriteDetails {
}
