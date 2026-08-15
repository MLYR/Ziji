package app.ziji.account.application;

/** 当前用户对可见账户缺少资料修改权限；HTTP 边界映射为 403。 */
public final class AccountPermissionDeniedException extends RuntimeException {

	public AccountPermissionDeniedException() {
		super("无权修改账户资料。");
	}
}
