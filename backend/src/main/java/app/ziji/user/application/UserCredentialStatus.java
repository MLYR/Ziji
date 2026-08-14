package app.ziji.user.application;

/**
 * 认证模块可见的用户凭据状态，属于 user application 公开契约；与 {@code user.domain.UserStatus}
 * 名称对齐但独立声明，避免 auth 跨模块依赖 user domain 类型。只有 ACTIVE 与 CLOSING 允许凭据认证。
 */
public enum UserCredentialStatus {
	ACTIVE,
	LOCKED,
	CLOSING,
	CLOSED
}
