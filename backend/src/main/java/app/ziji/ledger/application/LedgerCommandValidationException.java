package app.ziji.ledger.application;

/** 语义命令在应用边界的可理解校验失败。 */
public final class LedgerCommandValidationException extends IllegalArgumentException {

	public LedgerCommandValidationException(String message) {
		super(message);
	}
}
