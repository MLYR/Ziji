package app.ziji.ledger.application;

/** Ledger 事实事件的公开 outbox 写入端口；调用方事务负责原子提交。 */
public interface LedgerOutbox {

	void append(LedgerOutboxEvent event);
}
