package app.ziji.ledger.domain;

/** Transaction 的 V1 生命周期状态，与 V003 保持一致。 */
public enum TransactionStatus {
	DRAFT,
	POSTED,
	REVERSED,
	SUPERSEDED,
	DISCARDED
}
