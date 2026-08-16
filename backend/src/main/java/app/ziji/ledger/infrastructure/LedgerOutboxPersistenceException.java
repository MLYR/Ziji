package app.ziji.ledger.infrastructure;

/** Ledger outbox 基础设施失败必须传播，以回滚外层账务事务。 */
public class LedgerOutboxPersistenceException extends RuntimeException {

	public LedgerOutboxPersistenceException(String message, Throwable cause) {
		super(message, cause);
	}
}
