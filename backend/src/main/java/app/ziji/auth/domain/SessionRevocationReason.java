package app.ziji.auth.domain;

/** V011 固定的会话撤销原因；历史 NULL 基线由应用层降级为 SECURITY_ADMIN。 */
public enum SessionRevocationReason {
	REPLACED_BY_LOGIN,
	CURRENT_DEVICE,
	SELECTED_DEVICE,
	ALL_DEVICES,
	PASSWORD_RESET,
	REFRESH_TOKEN_REUSE,
	SESSION_EXPIRED,
	SECURITY_ADMIN
}
