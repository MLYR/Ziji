package app.ziji.ledger.domain;

/** Transaction 的 V1 类型，与 V003 保持一致，不在领域层扩展枚举。 */
public enum TransactionType {
	OPENING,
	INCOME,
	EXPENSE,
	REFUND,
	TRANSFER,
	FX_TRANSFER,
	ADJUSTMENT,
	INVESTMENT,
	REPAYMENT,
	INTEREST,
	REVERSAL
}
