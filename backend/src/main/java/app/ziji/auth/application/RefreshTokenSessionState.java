package app.ziji.auth.application;

import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.StoredRefreshToken;

/** 被行锁保护的 Token 与所属稳定会话快照；仅供正常轮换在同一事务内判定状态。 */
public final class RefreshTokenSessionState {

	private final DeviceSession session;
	private final StoredRefreshToken refreshToken;

	public RefreshTokenSessionState(DeviceSession session, StoredRefreshToken refreshToken) {
		if (session == null || refreshToken == null || !session.id().equals(refreshToken.sessionId())) {
			throw new IllegalArgumentException("刷新凭据会话状态无效。");
		}
		this.session = session;
		this.refreshToken = refreshToken;
	}

	public DeviceSession session() {
		return session;
	}

	public StoredRefreshToken refreshToken() {
		return refreshToken;
	}
}
