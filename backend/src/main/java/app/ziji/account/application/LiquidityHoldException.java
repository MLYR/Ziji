package app.ziji.account.application;

import app.ziji.account.domain.LiquidityHold;

/** LiquidityHold application 的稳定失败类型；HTTP 层据此映射既有 Problem Details。 */
public abstract class LiquidityHoldException extends RuntimeException {

	private LiquidityHoldException(String message) {
		super(message);
	}

	public static final class Validation extends LiquidityHoldException {
		public Validation() { super("流动性占用请求无效。"); }
	}

	public static final class BusinessRule extends LiquidityHoldException {
		public BusinessRule() { super("流动性占用当前不可操作。"); }
	}

	public static final class VersionConflict extends LiquidityHoldException {
		private final LiquidityHold current;

		public VersionConflict(LiquidityHold current) {
			super("流动性占用版本冲突。");
			this.current = current;
		}

		public LiquidityHold current() { return current; }
	}

	public static final class Persistence extends LiquidityHoldException {
		public Persistence(Throwable cause) { super("流动性占用持久化失败。"); initCause(cause); }
	}
}
