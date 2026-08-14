package app.ziji.auth.application;

/** 正常刷新轮换输入；原始刷新凭据仅在调用栈短暂存在，禁止自动 toString 泄漏。 */
public final class RotateRefreshTokenCommand {

	private final String refreshToken;

	public RotateRefreshTokenCommand(String refreshToken) {
		this.refreshToken = refreshToken;
	}

	public String refreshToken() {
		return refreshToken;
	}
}
