package app.ziji.account.application;

/** 账户归档的稳定业务失败；HTTP 和统一幂等边界分别将其映射为固定 Problem code。 */
public class AccountArchiveException extends RuntimeException {

	protected AccountArchiveException(String message) {
		super(message);
	}

	protected AccountArchiveException(String message, Throwable cause) {
		super(message, cause);
	}

	public static final class Validation extends AccountArchiveException {
		public Validation() { super("账户归档请求无效。"); }
	}

	public static final class AlreadyArchived extends AccountArchiveException {
		public AlreadyArchived() { super("账户已经归档。"); }
	}

	public static final class NonZeroBalanceConfirmationRequired extends AccountArchiveException {
		public NonZeroBalanceConfirmationRequired() { super("非零余额归档需要显式确认。"); }
	}

	/** 历史账户或版本引用无法安全重建时拒绝伪造首次响应。 */
	public static final class SafeReplayUnavailable extends AccountArchiveException {
		public SafeReplayUnavailable() { super("账户归档幂等结果无法安全重放。"); }
	}

	public static final class Persistence extends AccountArchiveException {
		public Persistence(Throwable cause) { super("账户归档持久化失败。", cause); }
	}
}
