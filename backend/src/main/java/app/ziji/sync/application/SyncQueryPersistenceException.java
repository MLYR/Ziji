package app.ziji.sync.application;

/** change_log 读取或持久化载荷解码失败；消息不包含 SQL、载荷或用户信息。 */
public final class SyncQueryPersistenceException extends RuntimeException {

	public SyncQueryPersistenceException(Throwable cause) {
		super("同步变更读取失败。", cause);
	}
}
