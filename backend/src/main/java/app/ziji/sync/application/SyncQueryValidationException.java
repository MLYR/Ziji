package app.ziji.sync.application;

/** 同步 limit 或游标无效时的安全错误，不携带原始游标或主体信息。 */
public final class SyncQueryValidationException extends RuntimeException {

	public SyncQueryValidationException() {
		super("同步查询参数无效。");
	}
}
