package app.ziji.ledger.domain;

/** Transaction 来源，与 V003 的 source CHECK 保持一致。 */
public enum TransactionSource {
	MANUAL,
	IMPORT,
	RECURRING,
	INVESTMENT,
	ADJUSTMENT,
	SYNC
}
