package app.ziji.liability.application;

import app.ziji.liability.domain.LiabilityDetail;

/** 负债详情 application 编排的稳定失败类型。 */
public abstract class LiabilityDetailApplicationException extends RuntimeException {

	private LiabilityDetailApplicationException(String message) {
		super(message);
	}

	public static final class NotFound extends LiabilityDetailApplicationException {
		public NotFound() { super("负债详情资源不存在。"); }
	}

	public static final class PermissionDenied extends LiabilityDetailApplicationException {
		public PermissionDenied() { super("当前成员不可写负债详情。"); }
	}

	public static final class VersionConflict extends LiabilityDetailApplicationException {
		private final LiabilityDetail current;

		public VersionConflict(LiabilityDetail current) {
			super("负债详情版本冲突。");
			if (current == null || current.version() < 1) {
				throw new IllegalArgumentException("版本冲突必须引用持久详情。");
			}
			this.current = current;
		}

		public LiabilityDetail current() { return current; }
	}

	public static final class SafeReplayUnavailable extends LiabilityDetailApplicationException {
		public SafeReplayUnavailable() { super("负债详情幂等结果无法安全重放。"); }
	}

	public static final class IdempotencyKeyReused extends LiabilityDetailApplicationException {
		public IdempotencyKeyReused() { super("幂等键已用于不同请求。"); }
	}

	public static final class IdempotencyInProgress extends LiabilityDetailApplicationException {
		public IdempotencyInProgress() { super("同一幂等请求仍在处理中。"); }
	}

	public static final class Persistence extends LiabilityDetailApplicationException {
		public Persistence(Throwable cause) {
			super("负债详情持久化失败。");
			initCause(cause);
		}
	}
}
