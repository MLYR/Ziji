package app.ziji.ledger.application;

/** 返回当前受控 HTTP 请求或系统任务的 correlation ID，不接受业务资源标识替代。 */
public interface LedgerRequestIdProvider {

	String currentRequestId();
}
