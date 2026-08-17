package app.ziji.liability.application;

import app.ziji.liability.domain.LiabilityDetailException;

/** PUT 的两个互斥条件：首次 If-None-Match:* 或持久详情强 If-Match。 */
public record LiabilityDetailPutCondition(Mode mode, int expectedVersion) {

	public enum Mode {
		INITIAL,
		REPLACE
	}

	public LiabilityDetailPutCondition {
		if (mode == null || mode == Mode.INITIAL && expectedVersion != 0
			|| mode == Mode.REPLACE && expectedVersion < 1) {
			throw new LiabilityDetailException.Validation();
		}
	}

	public static LiabilityDetailPutCondition initial() {
		return new LiabilityDetailPutCondition(Mode.INITIAL, 0);
	}

	public static LiabilityDetailPutCondition replace(int expectedVersion) {
		return new LiabilityDetailPutCondition(Mode.REPLACE, expectedVersion);
	}

	public boolean isInitial() {
		return mode == Mode.INITIAL;
	}

	public String preconditionValue() {
		return isInitial() ? "*" : "\"" + expectedVersion + "\"";
	}
}
