package app.ziji.ledger.domain;

/** 账务领域不变量校验失败时使用的明确异常。 */
public final class LedgerDomainException extends IllegalArgumentException {

	public LedgerDomainException(String message) {
		super(message);
	}
}
