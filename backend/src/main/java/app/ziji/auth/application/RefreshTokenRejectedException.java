package app.ziji.auth.application;

/** 刷新凭据安全拒绝；reason 仅供后续重用攻击处置识别，HTTP 层必须统一失败语义。 */
public final class RefreshTokenRejectedException extends RuntimeException {

	public enum Reason {
		INVALID,
		CONSUMED,
		REVOKED,
		EXPIRED,
		SESSION_REVOKED,
		SESSION_EXPIRED
	}

	private final Reason reason;

	public RefreshTokenRejectedException(Reason reason) {
		super("刷新凭据无效。");
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
