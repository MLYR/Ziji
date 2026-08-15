package app.ziji.ledger.domain;

/** LedgerAccount 的会计性质，必须与 V003/V007 的机器枚举保持一致。 */
public enum LedgerAccountNature {
	ASSET,
	LIABILITY,
	INCOME,
	EXPENSE,
	EQUITY,
	CLEARING
}
