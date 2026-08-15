package app.ziji.ledger.application;

/** 账务事实持久化失败；对外不泄露 SQL、账号或金额输入。 */
public final class LedgerPersistenceException extends RuntimeException {

	public LedgerPersistenceException(Throwable cause) {
		super("账务事实写入失败。", cause);
	}
}
