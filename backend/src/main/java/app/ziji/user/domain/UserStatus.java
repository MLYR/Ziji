package app.ziji.user.domain;

/** 用户认证与生命周期状态，LOCKED 仅表示认证安全锁定。 */
public enum UserStatus {
	ACTIVE,
	LOCKED,
	CLOSING,
	CLOSED
}
